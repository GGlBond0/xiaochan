package io.github.xiaocan.service.impl;

import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.enums.GrabModeEnums;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.vo.GrabResultVO;
import io.github.xiaocan.service.AutoGrabService;
import io.github.xiaocan.service.GrabService;
import io.github.xiaocan.service.LoginStateService;
import io.github.xiaocan.service.MonitoryConfigService;
import io.github.xiaocan.service.PushService;
import io.github.xiaocan.tasks.GrabCronScheduler;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 监控命中后自动抢单实现（多账号多平台优先级轮询）。
 *
 * 限频语义（实测 HAR ProxyPin7-17_04_34_29）：上游 code==70 = (账号×门店) 限频，
 * 跨活动跨平台共享，全平台一致。故多账号下"换号"可破限频。
 *
 * 一次命中按 storeId 分组（BaseTask 已分组调用），每组 = 同门店所有 (活动,平台) 组合。
 *  - SINGLE：按平台优先级选组合 → 组合内按账号优先级依次试；未到 start 建定时任务到 start；
 *    code==70/饭票不足/登录态过期 → 换下一账号；code==6/详情缺失/黑名单/未知 → 降级下一组合；
 *    成功即停；全部组合耗尽 → 失败通知。优先级硬约束：等待最高优先级期间不碰低优先级。
 *  - ALL：每个勾选账号并行独立、不换号；各账号在所有命中门店按平台优先级抢，
 *    某账号在某门店失败(code==70 等)即放弃该门店、转下一门店。
 *
 * 架构方案 C：立即抢在 grabExecutor 内存循环换号；仅"未到 start 的最高优先级组合"落一条
 * auto=0 grab_config 走 GrabCronScheduler 定时，到点回调回到内存循环从游标继续。
 *
 * 防重键：(userId,promotionId,loginStateId,当天,auto=1,lastGrabTime IS NULL) —— 不同账号可并行抢同活动。
 */
@Slf4j
@Service
public class AutoGrabServiceImpl implements AutoGrabService {

    /** 美团平台 type 值 */
    private static final int PLATFORM_MEITUAN = 1;
    /** 占位"执行中"标记，仅作状态展示；防重键是 lastGrabTime（见类注释） */
    private static final String RUNNING_MARK = "执行中";

    @Resource
    private GrabService grabService;
    @Resource
    private LoginStateService loginStateService;
    @Resource
    private GrabCronScheduler grabCronScheduler;
    @Resource
    private PushService pushService;
    @Resource
    private MonitoryConfigService monitoryConfigService;

    /** 立即抢异步执行池，独立于 taskScheduler，避免阻塞 monitor-cron 线程 */
    private final ExecutorService grabExecutor = Executors.newCachedThreadPool();

    @Override
    public Long tryCreateFromMonitor(MonitorConfigEntity config, List<StoreInfo> sameStoreCombos) {
        if (config == null || sameStoreCombos == null || sameStoreCombos.isEmpty()) return null;
        if (!Boolean.TRUE.equals(config.getAutoGrab())) return null;

        Integer userId = config.getUserId();

        // 1. 账号优先级列表（过滤过期/不存在，保序）
        List<Integer> accountIds = parseAccountIds(config);
        List<Integer> validAccounts = filterValidAccounts(accountIds, userId);
        if (validAccounts.isEmpty()) {
            log.warn("自动抢单跳过(无可用账号): userId={}, configId={}", userId, config.getId());
            pushExpireReminder(config, null);
            return null;
        }

        // 2. 平台优先级（有序），过滤出勾选平台对应的组合并按优先级排序
        List<Integer> platformOrder = parsePlatformOrder(config.getGrabPlatforms());
        List<StoreInfo> orderedCombos = new ArrayList<>();
        for (Integer plat : platformOrder) {
            for (StoreInfo s : sameStoreCombos) {
                if (s.getType() != null && s.getType().equals(plat) && s.getPromotionId() != null) {
                    orderedCombos.add(s);
                }
            }
        }
        if (orderedCombos.isEmpty()) {
            log.info("自动抢单跳过(无勾选平台命中): userId={}, configId={}", userId, config.getId());
            return null;
        }

        GrabModeEnums mode = config.getGrabMode() == null ? GrabModeEnums.SINGLE : config.getGrabMode();

        if (mode == GrabModeEnums.ALL) {
            // ALL：每个账号并行独立、不换号，各在所有命中门店抢。本调用只处理一个门店组，
            // 故每个账号在本门店组内按平台优先级组合降级、只用自己试。
            for (Integer account : validAccounts) {
                grabExecutor.submit(() -> runAllForAccount(config, userId, account, orderedCombos));
            }
            return null;
        }

        // SINGLE：立即抢内存循环（同步在 grabExecutor 内换号/降级）
        // 活动级成功防重：当天已抢成功(任意账号)的 promotionId 整组跳过，
        // 避免换号补抢同一活动导致 SINGLE 抢出多个名额（见 prd 根因）。
        Set<Integer> grabbed = grabbedSuccessPromotionIds(userId);
        if (!grabbed.isEmpty()) {
            List<StoreInfo> filtered = new ArrayList<>();
            for (StoreInfo s : orderedCombos) {
                if (s.getPromotionId() == null || !grabbed.contains(s.getPromotionId())) {
                    filtered.add(s);
                }
            }
            if (filtered.isEmpty()) {
                log.info("自动抢单跳过(活动已抢成功): userId={}, configId={}", userId, config.getId());
                return null;
            }
            final List<StoreInfo> combos = filtered;
            grabExecutor.submit(() -> runSingle(config, userId, validAccounts, combos, 0, 0));
            return null;
        }
        final List<StoreInfo> combos = orderedCombos;
        grabExecutor.submit(() -> runSingle(config, userId, validAccounts, combos, 0, 0));
        return null;
    }

    // ==================== SINGLE 模式 ====================

    /**
     * SINGLE 立即抢循环：从 (comboIdx, accountIdx) 游标起，按平台优先级组合降级、组合内按账号优先级换号。
     * comboIdx=0 为最高优先级组合。等待最高优先级组合到 start 期间不碰低优先级（硬约束）。
     */
    private void runSingle(MonitorConfigEntity config, Integer userId, List<Integer> accounts,
                           List<StoreInfo> combos, int comboIdx, int accountIdx) {
        LocalDateTime now = LocalDateTime.now();
        // 组合遍历（降级）
        for (int ci = comboIdx; ci < combos.size(); ci++) {
            StoreInfo store = combos.get(ci);
            // 时间判断（当天 HH:MM）
            TimeWindow tw = timeWindow(store, now);
            if (tw == TimeWindow.BEFORE_START) {
                // 未到 start：为该最高优先级可等待组合建定时任务到 start，不碰低优先级（硬约束）
                scheduleDeferredForCombo(config, store, userId, accounts.get(0), ci, 0, combos);
                return;
            }
            if (tw == TimeWindow.EXPIRED) {
                log.info("自动抢单跳过(组合已过期): userId={}, promotionId={}", userId, store.getPromotionId());
                continue; // 降级下一组合
            }
            // 时段内：组合内按账号优先级换号
            int startAi = (ci == comboIdx) ? accountIdx : 0;
            ComboOutcome outcome = tryComboWithAccounts(config, userId, store, accounts, startAi);
            if (outcome == ComboOutcome.SUCCESS) return;
            if (outcome == ComboOutcome.DEFERRED) return; // 已落定时（不应发生，时段内分支），防御
            // FAILED → 降级下一组合（continue）
        }
        // 全部组合耗尽
        pushAllFailed(config, userId, combos);
    }

    /** 组合内按账号优先级依次试。返回成功/落定时/失败。 */
    private ComboOutcome tryComboWithAccounts(MonitorConfigEntity config, Integer userId, StoreInfo store,
                                              List<Integer> accounts, int startAi) {
        for (int ai = startAi; ai < accounts.size(); ai++) {
            Integer account = accounts.get(ai);
            // 防重：同账号同活动当天已有未消费占位 → 换下一账号
            if (hasPlaceholder(userId, store.getPromotionId(), account)) {
                log.info("自动抢单跳过(同账号已有未消费占位): userId={}, promotionId={}, account={}",
                        userId, store.getPromotionId(), account);
                continue;
            }
            GrabConfigEntity entity = buildEntity(config, store, userId, store.getPromotionId(), account);
            entity.setAuto(true);
            entity.setStatus(MonitorConfigStatusEnums.ENABLE);
            entity.setExecuteAt(LocalDateTime.now());
            entity.setCron(null);
            entity.setLastResult(RUNNING_MARK);
            entity.setMonitorConfigId(config.getId());
            grabService.save(entity);
            log.info("自动抢单已建占位(立即抢): grabConfigId={}, userId={}, promotionId={}, account={}",
                    entity.getId(), userId, store.getPromotionId(), account);

            GrabResultVO result = grabService.doGrab(entity, "AUTO");
            if (result != null && Boolean.TRUE.equals(result.getSuccess())) {
                return ComboOutcome.SUCCESS;
            }
            int code = result == null ? -1 : (result.getCode() == null ? -1 : result.getCode());
            String msg = result == null ? "执行失败" : result.getMsg();
            // 兜底回写 lastGrabTime 标记已消费（失败也放行后续命中），更新 lastResult
            markConsumed(entity.getId(), code, msg);
            if (shouldSwitchAccount(code)) {
                log.info("自动抢单换号: userId={}, promotionId={}, account={}, code={}, msg={}",
                        userId, store.getPromotionId(), account, code, msg);
                continue; // 换下一账号
            }
            // code==6/详情缺失/黑名单/未知 → 该组合失败，降级下一组合
            log.info("自动抢单降级组合: userId={}, promotionId={}, account={}, code={}, msg={}",
                    userId, store.getPromotionId(), account, code, msg);
            return ComboOutcome.FAILED;
        }
        // 账号循环走完仍未成功 → 该组合失败，降级
        return ComboOutcome.FAILED;
    }

    // ==================== ALL 模式 ====================

    /** ALL：单账号在本门店组内按平台优先级组合降级，只用自己试，不换号。 */
    private void runAllForAccount(MonitorConfigEntity config, Integer userId, Integer account, List<StoreInfo> combos) {
        LocalDateTime now = LocalDateTime.now();
        for (StoreInfo store : combos) {
            TimeWindow tw = timeWindow(store, now);
            if (tw == TimeWindow.BEFORE_START) {
                // ALL 下该账号对该组合未到 start：建定时任务到 start，只用自己试
                scheduleDeferredForCombo(config, store, userId, account, indexOf(combos, store), 0, combos);
                return; // 等最高优先级组合，不碰低优先级（与 SINGLE 一致的硬约束）
            }
            if (tw == TimeWindow.EXPIRED) continue;
            // 时段内：只用自己试一次
            if (hasPlaceholder(userId, store.getPromotionId(), account)) continue; // 已有占位，跳过该组合
            GrabConfigEntity entity = buildEntity(config, store, userId, store.getPromotionId(), account);
            entity.setAuto(true);
            entity.setStatus(MonitorConfigStatusEnums.ENABLE);
            entity.setExecuteAt(LocalDateTime.now());
            entity.setCron(null);
            entity.setLastResult(RUNNING_MARK);
            entity.setMonitorConfigId(config.getId());
            grabService.save(entity);
            GrabResultVO result = grabService.doGrab(entity, "AUTO");
            if (result != null && Boolean.TRUE.equals(result.getSuccess())) return; // 该账号拿到一个名额
            int code = result == null ? -1 : (result.getCode() == null ? -1 : result.getCode());
            markConsumed(entity.getId(), code, result == null ? "执行失败" : result.getMsg());
            if (shouldSwitchAccount(code)) {
                // code==70/饭票不足/登录态过期：ALL 不换号 → 该账号放弃该门店，转下一门店
                // （本调用只一个门店组，故直接结束本账号本轮）
                log.info("自动抢单ALL放弃门店: userId={}, account={}, promotionId={}, code={}",
                        userId, account, store.getPromotionId(), code);
                return;
            }
            // code==6/详情缺失/黑名单/未知 → 该组合失败，降级该门店下一组合（continue）
        }
        // 该账号本门店组所有组合都未成功（无失败通知，ALL 各账号独立成败）
    }

    // ==================== 定时等待（到点回调） ====================

    /** 为"未到 start 的组合"落一条 auto=0 定时任务，executeAt=该组合 start，到点 GrabCronScheduler 触发。
     *  同时存同门店所有组合快照(comboSnapshot)，供到点/降级重建组合列表。 */
    private void scheduleDeferredForCombo(MonitorConfigEntity config, StoreInfo store, Integer userId,
                                          Integer account, int comboIdx, int accountIdx,
                                          List<StoreInfo> allCombos) {
        // 防重：同账号同活动当天已有 ENABLE 定时任务则跳过
        long exists = grabService.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, userId)
                .eq(GrabConfigEntity::getPromotionId, store.getPromotionId())
                .eq(GrabConfigEntity::getLoginStateId, account)
                .eq(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.ENABLE)
                .apply("DATE(create_time) = CURDATE()")
                .count();
        if (exists > 0) {
            log.info("自动抢单跳过(定时任务已存在): userId={}, promotionId={}, account={}",
                    userId, store.getPromotionId(), account);
            return;
        }
        LocalDateTime start = LocalDateTime.now().toLocalDate().atTime(parseHHMM(store.getStartTime(), LocalTime.MIDNIGHT));
        GrabConfigEntity entity = buildEntity(config, store, userId, store.getPromotionId(), account);
        entity.setAuto(false);
        entity.setStatus(MonitorConfigStatusEnums.ENABLE);
        entity.setExecuteAt(start);
        entity.setCron(null);
        entity.setMonitorConfigId(config.getId());
        entity.setGrabSeq(comboIdx + ":" + accountIdx);
        entity.setComboSnapshot(serializeCombos(allCombos));
        grabService.save(entity);
        log.info("自动抢单已建定时(等待到start): grabConfigId={}, userId={}, promotionId={}, account={}, start={}",
                entity.getId(), userId, store.getPromotionId(), account, start);
        grabCronScheduler.refresh(entity.getId());
    }

    // ==================== 到点回调入口（供 GrabCronScheduler.execute 调用） ====================

    /**
     * 定时任务到点触发后继续推进换号/降级。GrabCronScheduler.execute 对带 monitorConfigId 的
     * ONESHOT 任务改调本方法：从 comboSnapshot 重建组合列表、重读 monitor_config 账号/平台优先级/模式，
     * 按 grabSeq 游标转入 SINGLE/ALL 循环从断点继续。
     */
    @Override
    public void onScheduledFire(GrabConfigEntity scheduled) {
        if (scheduled == null || scheduled.getMonitorConfigId() == null) {
            grabService.doGrab(scheduled, "ONESHOT");
            return;
        }
        try {
            MonitorConfigEntity config = monitoryConfigService.getById(scheduled.getMonitorConfigId());
            if (config == null || !Boolean.TRUE.equals(config.getAutoGrab())) {
                log.info("到点回调跳过(配置不存在或未开启autoGrab): grabConfigId={}", scheduled.getId());
                return;
            }
            Integer userId = config.getUserId();
            List<StoreInfo> combos = deserializeCombos(scheduled.getComboSnapshot());
            if (combos == null || combos.isEmpty()) {
                // 无快照回退：只对当前 grab_config 对应单组合单账号抢一次
                log.warn("到点回调无组合快照,回退单次抢: grabConfigId={}", scheduled.getId());
                grabService.doGrab(scheduled, "ONESHOT");
                return;
            }
            int comboIdx = 0, accountIdx = 0;
            if (StringUtils.hasText(scheduled.getGrabSeq())) {
                String[] p = scheduled.getGrabSeq().split(":");
                try { comboIdx = Integer.parseInt(p[0]); accountIdx = p.length > 1 ? Integer.parseInt(p[1]) : 0; }
                catch (NumberFormatException ignore) {}
            }
            // 占位定时任务已完成使命，置 DISABLE 避免重复触发
            grabService.lambdaUpdate().eq(GrabConfigEntity::getId, scheduled.getId())
                    .set(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.DISABLE).update();
            grabCronScheduler.cancel(scheduled.getId());

            GrabModeEnums mode = config.getGrabMode() == null ? GrabModeEnums.SINGLE : config.getGrabMode();
            if (mode == GrabModeEnums.ALL) {
                // ALL 到点：原账号继续本门店剩余组合（不换号）
                Integer account = scheduled.getLoginStateId();
                runAllForAccountFrom(config, userId, account, combos, comboIdx);
            } else {
                List<Integer> validAccounts = filterValidAccounts(parseAccountIds(config), userId);
                if (validAccounts.isEmpty()) { pushExpireReminder(config, null); return; }
                runSingle(config, userId, validAccounts, combos, comboIdx, accountIdx);
            }
        } catch (Exception e) {
            log.error("到点回调异常 grabConfigId={}: {}", scheduled.getId(), e.getMessage(), e);
        }
    }

    /** ALL 到点续推：从 comboIdx 起对该账号继续本门店剩余组合（只用自己、不换号）。 */
    private void runAllForAccountFrom(MonitorConfigEntity config, Integer userId, Integer account,
                                      List<StoreInfo> combos, int comboIdx) {
        LocalDateTime now = LocalDateTime.now();
        for (int ci = comboIdx; ci < combos.size(); ci++) {
            StoreInfo store = combos.get(ci);
            TimeWindow tw = timeWindow(store, now);
            if (tw == TimeWindow.BEFORE_START) {
                scheduleDeferredForCombo(config, store, userId, account, ci, 0, combos);
                return;
            }
            if (tw == TimeWindow.EXPIRED) continue;
            if (hasPlaceholder(userId, store.getPromotionId(), account)) continue;
            GrabConfigEntity entity = buildEntity(config, store, userId, store.getPromotionId(), account);
            entity.setAuto(true); entity.setStatus(MonitorConfigStatusEnums.ENABLE);
            entity.setExecuteAt(now); entity.setCron(null); entity.setLastResult(RUNNING_MARK);
            entity.setMonitorConfigId(config.getId());
            grabService.save(entity);
            GrabResultVO r = grabService.doGrab(entity, "AUTO");
            if (r != null && Boolean.TRUE.equals(r.getSuccess())) return;
            int code = r == null ? -1 : (r.getCode() == null ? -1 : r.getCode());
            markConsumed(entity.getId(), code, r == null ? "执行失败" : r.getMsg());
            if (shouldSwitchAccount(code)) return; // 该账号放弃本门店
        }
    }

    // ==================== 判定表（design §4.2 / R8） ====================

    /** true=换号（账号相关失败），false=该组合失败降级（组合/活动相关失败）。 */
    private boolean shouldSwitchAccount(int code) {
        // code==70 限频、-1 饭票不足/登录态过期 → 换号
        // code==6 已抢完、其它 → 降级组合
        return code == 70 || code == -1;
    }

    // ==================== 辅助 ====================

    private enum TimeWindow { BEFORE_START, IN_WINDOW, EXPIRED }

    private enum ComboOutcome { SUCCESS, FAILED, DEFERRED }

    private TimeWindow timeWindow(StoreInfo store, LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        LocalTime startLt = parseHHMM(store.getStartTime(), LocalTime.MIDNIGHT);
        LocalTime endLt = parseHHMM(store.getEndTime(), LocalTime.MAX);
        LocalDateTime start = today.toLocalDate().atTime(startLt);
        LocalDateTime end = today.toLocalDate().atTime(endLt);
        if (now.isBefore(start)) return TimeWindow.BEFORE_START;
        if (now.isAfter(end) || now.isEqual(end)) return TimeWindow.EXPIRED;
        return TimeWindow.IN_WINDOW;
    }

    /** 防重：同账号同活动当天 auto=1 且 lastGrabTime IS NULL 的占位存在则 true。 */
    private boolean hasPlaceholder(Integer userId, Integer promotionId, Integer account) {
        if (promotionId == null || account == null) return false;
        long c = grabService.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, userId)
                .eq(GrabConfigEntity::getPromotionId, promotionId)
                .eq(GrabConfigEntity::getLoginStateId, account)
                .eq(GrabConfigEntity::getAuto, true)
                .apply("DATE(create_time) = CURDATE()")
                .isNull(GrabConfigEntity::getLastGrabTime)
                .count();
        return c > 0;
    }

    /**
     * SINGLE 活动级成功防重：返回当天已被本配置以任意账号抢成功的 promotionId 集合。
     * 判定：grab_config 当天、auto=true、status=DISABLE、lastResult like '成功%'。
     * 兼容美团(成功 orderId=..)与饿了么/京东(成功)；status=DISABLE 双重锁定，避免失败记录误判。
     */
    private Set<Integer> grabbedSuccessPromotionIds(Integer userId) {
        List<GrabConfigEntity> rows = grabService.lambdaQuery()
                .select(GrabConfigEntity::getPromotionId)
                .eq(GrabConfigEntity::getUserId, userId)
                .eq(GrabConfigEntity::getAuto, true)
                .eq(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.DISABLE)
                .likeRight(GrabConfigEntity::getLastResult, "成功")
                .apply("DATE(create_time) = CURDATE()")
                .list();
        return rows.stream()
                .map(GrabConfigEntity::getPromotionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** 失败兜底：回写 lastGrabTime 标记已消费，更新 lastResult。 */
    private void markConsumed(Integer configId, int code, String msg) {
        try {
            grabService.lambdaUpdate().eq(GrabConfigEntity::getId, configId)
                    .set(GrabConfigEntity::getLastResult, "失败:code=" + code + "," + msg)
                    .set(GrabConfigEntity::getLastGrabTime, LocalDateTime.now())
                    .update();
        } catch (Exception ignore) { /* 尽力而为 */ }
    }

    /** 组装 grab_config 基础字段 + 活动快照（复用 GrabServiceImpl 默认值约定）。 */
    private GrabConfigEntity buildEntity(MonitorConfigEntity config, StoreInfo store,
                                         Integer userId, Integer promotionId, Integer account) {
        GrabConfigEntity entity = new GrabConfigEntity();
        entity.setUserId(userId);
        entity.setLoginStateId(account);
        entity.setLocationId(config.getLocationId());
        entity.setPromotionId(promotionId);
        entity.setStorePlatform(store.getType() == null ? 1 : store.getType());
        entity.setIfAdvanceOrder(false);
        entity.setLeadMs(0);
        entity.setEnableRetry(true);
        entity.setMaxRetry(3);
        entity.setRetryIntervalMs(500);
        entity.setSilkId(0);
        entity.setStoreName(store.getName());
        entity.setPromoDetail(buildPromoDetail(store));
        entity.setStartTime(store.getStartTime());
        entity.setEndTime(store.getEndTime());
        return entity;
    }

    /** 解析账号优先级列表：grab_login_state_ids 空 → 回退 grab_login_state_id 单值。保序。 */
    private List<Integer> parseAccountIds(MonitorConfigEntity config) {
        List<Integer> list = new ArrayList<>();
        String ids = config.getGrabLoginStateIds();
        if (StringUtils.hasText(ids)) {
            for (String s : ids.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    try { list.add(Integer.parseInt(t)); } catch (NumberFormatException ignore) {}
                }
            }
        }
        if (list.isEmpty() && config.getGrabLoginStateId() != null) {
            list.add(config.getGrabLoginStateId());
        }
        return list;
    }

    /** 过滤过期/不存在的账号，保序。空列表时推送登录态过期提醒。 */
    private List<Integer> filterValidAccounts(List<Integer> accountIds, Integer userId) {
        List<Integer> valid = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Integer id : accountIds) {
            if (id == null) continue;
            LoginStateEntity ls = loginStateService.getEntityByIdAndUser(id, userId);
            if (ls != null && ls.getExpireAt() != null && !ls.getExpireAt().isBefore(now)) {
                valid.add(id);
            }
        }
        return valid;
    }

    /** 解析平台优先级（有序），null/空 → [1]（仅美团，向后兼容）。 */
    private List<Integer> parsePlatformOrder(String grabPlatforms) {
        List<Integer> order = new ArrayList<>();
        if (StringUtils.hasText(grabPlatforms)) {
            for (String s : grabPlatforms.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    try { order.add(Integer.parseInt(t)); } catch (NumberFormatException ignore) {}
                }
            }
        }
        if (order.isEmpty()) order.add(PLATFORM_MEITUAN);
        return order;
    }

    private int indexOf(List<StoreInfo> combos, StoreInfo s) {
        for (int i = 0; i < combos.size(); i++) if (combos.get(i) == s) return i;
        return 0;
    }

    /** 序列化同门店组合快照（保留 doGrab + 时间判断 + 快照所需字段）。 */
    private String serializeCombos(List<StoreInfo> combos) {
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(combos);
        } catch (Exception e) {
            log.error("序列化组合快照失败", e);
            return null;
        }
    }

    /** 反序列化组合快照。 */
    private List<StoreInfo> deserializeCombos(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return com.alibaba.fastjson2.JSON.parseArray(json, StoreInfo.class);
        } catch (Exception e) {
            log.warn("反序列化组合快照失败: {}", e.getMessage());
            return null;
        }
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

    /** 优惠明细：满X返Y；金额为空时返回 null。 */
    private String buildPromoDetail(StoreInfo s) {
        if (s == null) return null;
        if (s.getPrice() != null && s.getRebatePrice() != null) {
            return "满" + s.getPrice().stripTrailingZeros().toPlainString()
                    + "返" + s.getRebatePrice().stripTrailingZeros().toPlainString();
        }
        return null;
    }

    /** 全部组合耗尽未成功 → 推送失败通知（含尝试概要）。 */
    private void pushAllFailed(MonitorConfigEntity config, Integer userId, List<StoreInfo> combos) {
        try {
            String names = combos.stream().map(StoreInfo::getName).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("(未知门店)");
            String content = "自动抢单未成功：门店「" + names + "」所有勾选平台/账号尝试均失败。";
            if (config.getLocationId() != null) {
                pushService.pushToLocation(config.getLocationId(), content, "自动抢单失败");
            } else {
                pushService.pushToUser(userId, content, "自动抢单失败");
            }
        } catch (Exception e) {
            log.error("推送自动抢单失败通知异常", e);
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

    @PreDestroy
    public void shutdown() {
        grabExecutor.shutdown();
    }
}
