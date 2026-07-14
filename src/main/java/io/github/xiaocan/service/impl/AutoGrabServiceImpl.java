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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 监控命中后自动建立抢单任务实现。
 *
 * 决策A：仅对美团(type=1)活动自动抢；非美团活动命中只通知不建任务。
 * 防重：同 userId + promotionId + 当天 + ENABLE 的 grab_config 已存在则跳过。
 * 时间分支：未到点建定时任务(executeAt=当天startTime)，在时段内立即抢(executeAt=now)，过期跳过。
 * 登录态过期：跳过建任务并推送提醒。
 */
@Slf4j
@Service
public class AutoGrabServiceImpl implements AutoGrabService {

    /** 美团平台 type 值 */
    private static final int PLATFORM_MEITUAN = 1;

    @Resource
    private GrabService grabService;
    @Resource
    private LoginStateService loginStateService;
    @Resource
    private GrabCronScheduler grabCronScheduler;
    @Resource
    private PushService pushService;

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

        // 2. 防重：同用户同活动当天已有 ENABLE 任务则跳过
        long exists = grabService.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, userId)
                .eq(GrabConfigEntity::getPromotionId, promotionId)
                .eq(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.ENABLE)
                .apply("DATE(create_time) = CURDATE()")
                .count();
        if (exists > 0) {
            log.info("自动抢单跳过(已存在): userId={}, promotionId={}", userId, promotionId);
            return null;
        }

        // 3. 登录态校验
        LoginStateEntity loginState = loginStateService.getEntityByIdAndUser(config.getGrabLoginStateId(), userId);
        LocalDateTime now = LocalDateTime.now();
        if (loginState == null || loginState.getExpireAt() == null || loginState.getExpireAt().isBefore(now)) {
            log.warn("自动抢单跳过(登录态过期或不存在): userId={}, loginStateId={}", userId, config.getGrabLoginStateId());
            pushExpireReminder(config, loginState);
            return null;
        }

        // 4. 时间判断（当天 + HH:MM）
        LocalDate today = now.toLocalDate();
        LocalTime startLt = parseHHMM(store.getStartTime(), LocalTime.MIDNIGHT);
        LocalTime endLt = parseHHMM(store.getEndTime(), LocalTime.MAX);
        LocalDateTime start = today.atTime(startLt);
        LocalDateTime end = today.atTime(endLt);

        LocalDateTime executeAt;
        if (now.isBefore(start)) {
            executeAt = start;      // 定时到点抢
        } else if (now.isBefore(end)) {
            executeAt = now;        // 在时段内立即抢
        } else {
            log.info("自动抢单跳过(活动已过期): userId={}, promotionId={}, end={}", userId, promotionId, end);
            return null;
        }

        // 5. 组装 grab_config（复用 GrabServiceImpl.addUpdateConfig 默认值约定）
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
        entity.setStatus(MonitorConfigStatusEnums.ENABLE);
        entity.setExecuteAt(executeAt);
        grabService.save(entity);
        log.info("自动抢单已建任务: grabConfigId={}, userId={}, promotionId={}, executeAt={}",
                entity.getId(), userId, promotionId, executeAt);

        // 6. 注册调度
        grabCronScheduler.refresh(entity.getId());

        return entity.getId() == null ? null : entity.getId().longValue();
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
}
