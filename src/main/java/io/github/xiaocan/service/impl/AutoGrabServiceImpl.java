package io.github.xiaocan.service.impl;

import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.service.AutoGrabService;
import io.github.xiaocan.service.GrabService;
import io.github.xiaocan.service.LoginStateService;
import io.github.xiaocan.service.PushService;
import io.github.xiaocan.tasks.GrabCronScheduler;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 监控命中后自动建立抢单任务实现。
 *
 * 决策A：仅对美团(type=1)活动自动抢；非美团活动命中只通知不建任务。
 *
 * 时间分支：
 *  - 未到点（now < start）：建定时任务(auto=0, executeAt=当天startTime)，注册 cron，进前端列表。
 *  - 在时段内（start <= now < end）：建占位(auto=1)，不注册 cron、不进前端列表，异步直接 doGrab("AUTO")。
 *  - 已过期（now >= end）：跳过。
 *
 * 防重（立即抢）：同 userId + promotionId + 当天 + auto=1 且 lastGrabTime IS NULL 且 lastResult IS NULL
 *  的占位存在则跳过（完全未触碰的占位才挡）。doGrab 前先把 lastResult 置"执行中"，确保即使回调失败也阻止重复抢。
 *
 * 登录态过期：跳过建任务并推送提醒。
 */
@Slf4j
@Service
public class AutoGrabServiceImpl implements AutoGrabService {

    /** 美团平台 type 值 */
    private static final int PLATFORM_MEITUAN = 1;
    /** 占位"执行中"标记，防重看 lastResult IS NULL，执行前置此值避免回调失败导致重复抢 */
    private static final String RUNNING_MARK = "执行中";

    @Resource
    private GrabService grabService;
    @Resource
    private LoginStateService loginStateService;
    @Resource
    private GrabCronScheduler grabCronScheduler;
    @Resource
    private PushService pushService;

    /** 立即抢异步执行池，独立于 taskScheduler，避免阻塞 monitor-cron 线程 */
    private final ExecutorService grabExecutor = Executors.newCachedThreadPool();

    @Override
    public Long tryCreateFromMonitor(MonitorConfigEntity config, StoreInfo store) {
        if (config == null || store == null) return null;
        // 1. 开关门禁
        if (!Boolean.TRUE.equals(config.getAutoGrab())) return null;
        // 决策A：仅美团
        if (store.getType() == null || store.getType() != PLATFORM_MEITUAN) return null;
        if (store.getPromotionId() == null) return null;

        Integer userId = config.getUserId();
        Integer promotionId = store.getPromotionId();

        // 2. 登录态校验（提前，避免无谓落库）
        LoginStateEntity loginState = loginStateService.getEntityByIdAndUser(config.getGrabLoginStateId(), userId);
        LocalDateTime now = LocalDateTime.now();
        if (loginState == null || loginState.getExpireAt() == null || loginState.getExpireAt().isBefore(now)) {
            log.warn("自动抢单跳过(登录态过期或不存在): userId={}, loginStateId={}", userId, config.getGrabLoginStateId());
            pushExpireReminder(config, loginState);
            return null;
        }

        // 3. 时间判断（当天 + HH:MM）
        LocalDate today = now.toLocalDate();
        LocalTime startLt = parseHHMM(store.getStartTime(), LocalTime.MIDNIGHT);
        LocalTime endLt = parseHHMM(store.getEndTime(), LocalTime.MAX);
        LocalDateTime start = today.atTime(startLt);
        LocalDateTime end = today.atTime(endLt);

        boolean immediate = !now.isBefore(start) && now.isBefore(end); // 在时段内立即抢
        if (!immediate && now.isBefore(start)) {
            // 定时抢分支
            return scheduleDeferred(config, store, userId, promotionId, start);
        }
        if (!immediate) {
            // now >= end，活动已过期
            log.info("自动抢单跳过(活动已过期): userId={}, promotionId={}, end={}", userId, promotionId, end);
            return null;
        }

        // 4. 立即抢：防重——当天 auto=1 且 lastGrabTime IS NULL 的占位存在则跳过
        //  (执行前 lastGrabTime 始终为空，含"执行中"标记也挡；doGrab 成功/回调失败补写后 lastGrabTime 非 null 即放行)
        long placeholder = grabService.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, userId)
                .eq(GrabConfigEntity::getPromotionId, promotionId)
                .eq(GrabConfigEntity::getAuto, true)
                .apply("DATE(create_time) = CURDATE()")
                .isNull(GrabConfigEntity::getLastGrabTime)
                .count();
        if (placeholder > 0) {
            log.info("自动抢单跳过(已有未消费占位): userId={}, promotionId={}", userId, promotionId);
            return null;
        }

        // 5. 落占位（auto=1，不进前端列表、不注册 cron）
        GrabConfigEntity entity = buildBaseEntity(config, store, userId, promotionId);
        entity.setAuto(true);
        entity.setStatus(MonitorConfigStatusEnums.ENABLE);
        entity.setExecuteAt(now);   // 仅留痕，不用于调度
        entity.setCron(null);
        entity.setLastResult(RUNNING_MARK); // 占位状态展示；防重靠 lastGrabTime(执行前为null→挡, 回调补写后→放行)
        grabService.save(entity);
        log.info("自动抢单已建占位(立即抢): grabConfigId={}, userId={}, promotionId={}",
                entity.getId(), userId, promotionId);

        // 6. 异步直接执行 doGrab，不注册 cron
        final Integer configId = entity.getId();
        grabExecutor.submit(() -> {
            try {
                GrabConfigEntity latest = grabService.getById(configId);
                if (latest == null) return;
                var result = grabService.doGrab(latest, "AUTO");
                // doGrab 成功会自行写 lastResult/lastGrabTime/DISABLE。
                // 失败时 doGrab 不回写 lastGrabTime（占位仍 lastGrabTime NULL → 永久挡后续当天命中），
                // 这里兜底补写 lastGrabTime 标记"已消费"并更新 lastResult，放行后续命中再抢。
                if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                    String msg = result == null ? "执行失败" : result.getMsg();
                    grabService.lambdaUpdate().eq(GrabConfigEntity::getId, configId)
                            .set(GrabConfigEntity::getLastResult, "失败:" + msg)
                            .set(GrabConfigEntity::getLastGrabTime, java.time.LocalDateTime.now())
                            .update();
                }
            } catch (Exception e) {
                log.error("自动抢单异步执行异常 configId={}: {}", configId, e.getMessage(), e);
                // 兜底：异常时补写 lastGrabTime 标记"已消费"，避免占位永久挡后续当天命中
                try {
                    grabService.lambdaUpdate().eq(GrabConfigEntity::getId, configId)
                            .set(GrabConfigEntity::getLastResult, "执行异常:" + e.getMessage())
                            .set(GrabConfigEntity::getLastGrabTime, java.time.LocalDateTime.now())
                            .update();
                } catch (Exception ignore) { /* 尽力而为 */ }
            }
        });

        return entity.getId() == null ? null : entity.getId().longValue();
    }

    /** 定时抢分支：建任务(auto=0)+快照，注册 cron，进前端列表 */
    private Long scheduleDeferred(MonitorConfigEntity config, StoreInfo store, Integer userId,
                                  Integer promotionId, LocalDateTime start) {
        long exists = grabService.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, userId)
                .eq(GrabConfigEntity::getPromotionId, promotionId)
                .eq(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.ENABLE)
                .apply("DATE(create_time) = CURDATE()")
                .count();
        if (exists > 0) {
            log.info("自动抢单跳过(定时任务已存在): userId={}, promotionId={}", userId, promotionId);
            return null;
        }
        GrabConfigEntity entity = buildBaseEntity(config, store, userId, promotionId);
        entity.setAuto(false);
        entity.setStatus(MonitorConfigStatusEnums.ENABLE);
        entity.setExecuteAt(start);
        entity.setCron(null);
        grabService.save(entity);
        log.info("自动抢单已建任务(定时抢): grabConfigId={}, userId={}, promotionId={}, executeAt={}",
                entity.getId(), userId, promotionId, start);
        grabCronScheduler.refresh(entity.getId());
        return entity.getId() == null ? null : entity.getId().longValue();
    }

    /** 组装 grab_config 基础字段 + 活动快照（复用 GrabServiceImpl.addUpdateConfig 默认值约定） */
    private GrabConfigEntity buildBaseEntity(MonitorConfigEntity config, StoreInfo store,
                                             Integer userId, Integer promotionId) {
        GrabConfigEntity entity = new GrabConfigEntity();
        entity.setUserId(userId);
        entity.setLoginStateId(config.getGrabLoginStateId());
        entity.setLocationId(config.getLocationId());
        entity.setPromotionId(promotionId);
        entity.setStorePlatform(1);
        entity.setIfAdvanceOrder(false);
        entity.setLeadMs(0);
        entity.setEnableRetry(true);
        entity.setMaxRetry(3);
        entity.setRetryIntervalMs(500);
        entity.setSilkId(0);
        // 活动快照
        entity.setStoreName(store.getName());
        entity.setPromoDetail(buildPromoDetail(store));
        entity.setStartTime(store.getStartTime());
        entity.setEndTime(store.getEndTime());
        return entity;
    }

    /** 解析 "HH:MM" 为 LocalTime，失败回退到 fallback。 */
    private LocalTime parseHHMM(String hhmm, LocalTime fallback) {
        if (!StringUtils.hasText(hhmm)) return fallback;
        try {
            return LocalTime.parse(hhmm);
        } catch (DateTimeParseException e) {
            log.warn("解析活动时间失败回退: '{}' -> {}", hhmm, fallback);
            return fallback;
        }
    }

    /** 优惠明细：满X返Y；金额为空时返回 null。与 GrabServiceImpl.buildPromoDetail 一致。 */
    private String buildPromoDetail(StoreInfo s) {
        if (s == null) return null;
        if (s.getPrice() != null && s.getRebatePrice() != null) {
            return "满" + s.getPrice().stripTrailingZeros().toPlainString()
                    + "返" + s.getRebatePrice().stripTrailingZeros().toPlainString();
        }
        return null;
    }

    /** 登录态过期/缺失时推送提醒，按地址路由、user.spt 兜底。 */
    private void pushExpireReminder(MonitorConfigEntity config, LoginStateEntity loginState) {
        try {
            String summary = "自动抢单账号不可用";
            String name = loginState == null ? "(已删除)" : loginState.getName();
            String content = "自动抢单账号「" + name + "」已过期或不存在，监控命中的活动未自动抢单，请重新抓包录入登录态。";
            if (config.getLocationId() != null) {
                pushService.pushToLocation(config.getLocationId(), content, summary);
            } else {
                pushService.pushToUser(config.getUserId(), content, summary);
            }
        } catch (Exception e) {
            log.error("推送自动抢单登录态过期提醒失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        grabExecutor.shutdown();
    }
}
