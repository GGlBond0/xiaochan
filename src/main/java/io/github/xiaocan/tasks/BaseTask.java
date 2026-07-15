package io.github.xiaocan.tasks;

import io.github.xiaocan.constant.StorePlatformEnum;
import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.entity.TaskExecHistoryEntity;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.entity.StorePushedHistoryEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.service.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wangxiaodong
 * @date 2026/4/17
 */
@Slf4j
@Component
public class BaseTask {

    @Resource
    private MonitoryConfigService monitoryConfigService;
    @Resource
    private TaskExecHistoryService taskExecHistoryService;
    @Resource
    private LocationService locationService;
    @Resource
    private StorePushedHistoryService storePushedHistoryService;
    @Resource
    private UserService userService;
    @Resource
    private PushService pushService;
    @Resource
    private AutoGrabService autoGrabService;


    void runSingle(MonitorConfigEntity notifyConfig) {
        runSingle(notifyConfig, false);
    }

    void runSingle(MonitorConfigEntity notifyConfig, boolean ignoreTimeWindow) {
        TaskExecHistoryEntity execHistory = new TaskExecHistoryEntity();
        execHistory.setUserId(notifyConfig.getUserId());
        execHistory.setNotifyType(notifyConfig.getType());
        execHistory.setNotifyConfigId(notifyConfig.getId());
        execHistory.setStartTime(LocalDateTime.now());
        execHistory.setSuccess(true);
        log.info("开始执行type is {}, config id is {}", notifyConfig.getType().getDescription(), notifyConfig.getId());

        if (!ignoreTimeWindow && !StringUtils.hasText(notifyConfig.getCron())) {
            if (notifyConfig.getStartHour() == null || notifyConfig.getEndHour() == null || !StringUtils.hasText(notifyConfig.getWeeks())) {
                log.info("configId: {} 未配置 cron 且时间字段不完整，跳过执行", notifyConfig.getId());
                return;
            }
            int currentHour = LocalDateTime.now().getHour();
            if (currentHour < notifyConfig.getStartHour() || currentHour >= notifyConfig.getEndHour()) {
                log.info("当前时间{}不在运行时间范围{}-{}内，跳过执行 config id is {}", currentHour, notifyConfig.getStartHour(), notifyConfig.getEndHour(), notifyConfig.getId());
                return;
            }
            // 判断星期
            int currentDayOfWeek = LocalDateTime.now().getDayOfWeek().getValue();
            Set<Integer> weekSet = Arrays.stream(notifyConfig.getWeeks().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());
            if (!weekSet.contains(currentDayOfWeek)) {
                log.info("当前星期{}不在运行星期{}内，跳过执行 config id is {}", currentDayOfWeek, notifyConfig.getWeeks(), notifyConfig.getId());
                return;
            }
        }

        try {
            //获取地址
            Optional<LocationEntity> optionalLocation = locationService.getOptById(notifyConfig.getLocationId());
            if (optionalLocation.isEmpty()) {
                log.error("位置信息不存在 {} {}", notifyConfig.getId(), notifyConfig.getLocationId());
                monitoryConfigService.updateConfig(notifyConfig.getId(), MonitorConfigStatusEnums.DISABLE, "位置信息不存在");
                execHistory.setSuccess(false);
                execHistory.setRemark("执行失败：位置信息不存在");
                return;
            }
            LocationEntity location = optionalLocation.get();
            // 先清理本配置过期的推送记录（无命中也清理，确保过期记录不堆积）
            cleanupExpired(notifyConfig);
            List<StoreInfo> storeInfos = fetchStoreInfos(notifyConfig, execHistory, location);
            List<StoreInfo> availableStores = filterStoreInfos(notifyConfig, storeInfos);
            // 过滤掉当前已超过店铺营业时间（打烊）的活动：即使抢单成功也无法使用，最后还是要取消
            List<StoreInfo> openStores = availableStores.stream()
                    .filter(this::withinOpenHours)
                    .toList();
            int droppedByClosed = availableStores.size() - openStores.size();
            if (droppedByClosed > 0) {
                log.info("configId: {} 过滤掉{}个已打烊门店活动（当前时间超过店铺营业时间）",
                        notifyConfig.getId(), droppedByClosed);
            }
            availableStores = openStores;
            if(availableStores.isEmpty()){
                log.info("configId: {} 没有满足条件的门店活动", notifyConfig.getId());
                execHistory.setRemark("没有满足条件的门店活动");
                execHistory.setNotifyStoreCount(0);
                return;
            }
            execHistory.setNotifyStoreCount(availableStores.size());
            log.info("configId: {} 找到{}个满足条件的门店活动", notifyConfig.getId(), availableStores.size());
            savePushedHistory(notifyConfig, availableStores);
            afterSuccess(notifyConfig, availableStores);
            //通知
            sendMessage(notifyConfig, availableStores, location);
            // 监控命中后自动建立抢单任务（仅开启 autoGrab 的配置；AutoGrabService 内部按美团/防重/登录态/时间门禁）
            triggerAutoGrab(notifyConfig, availableStores);
        }catch (Exception e){
            log.error("执行异常 type {} config id is {}", notifyConfig.getType(), notifyConfig.getId(), e);
            execHistory.setSuccess(false);
            execHistory.setRemark("执行异常："+e.getMessage());
        }finally {
            execHistory.setEndTime(LocalDateTime.now());
            taskExecHistoryService.save(execHistory);
        }

    }

    protected List<StoreInfo> fetchStoreInfos(MonitorConfigEntity notifyConfig,
                                              TaskExecHistoryEntity execHistory,
                                              LocationEntity locationEntity){
        throw new UnsupportedOperationException("不支持的调用");
    }

    protected List<StoreInfo> filterStoreInfos(MonitorConfigEntity notifyConfig,
                                               List<StoreInfo> storeInfos){
        throw new UnsupportedOperationException("不支持的调用");
    }

    /**
     * 判断当前时间是否仍在店铺营业时间内。
     * 营业时间格式形如 "10:00-22:00"；空/格式不符视为仍营业（不误杀）。
     * 仅在"已打烊"（当前时间 >= 闭店时间）时过滤；未开门时段照常推送，因当天仍会开门、活动可能有效。
     */
    protected boolean withinOpenHours(StoreInfo storeInfo) {
        String openHours = storeInfo.getOpenHours();
        if (!StringUtils.hasText(openHours) || !openHours.contains("-")) {
            return true;
        }
        String[] parts = openHours.split("-", 2);
        try {
            LocalTime closeTime = LocalTime.parse(parts[1].trim());
            // 跨天营业（如 22:00-06:00）：闭店早于开门，说明营业横跨到次日清晨，
            // 此时仅当当前时间已超过次日打烊点（即 < closeTime）才视为打烊。
            LocalTime openTime = LocalTime.parse(parts[0].trim());
            LocalTime now = LocalTime.now();
            if (!closeTime.isAfter(openTime)) {
                // 跨天：营业中 => now >= openTime 或 now < closeTime；打烊 => 其它
                return now.isAfter(openTime) || now.isBefore(closeTime) || now.equals(openTime);
            }
            // 普通同日营业：仅 now < closeTime 才在营业；未开门(now < openTime)照常推送，故仅按 closeTime 判定
            return now.isBefore(closeTime);
        } catch (Exception e) {
            log.warn("解析营业时间失败，跳过过滤 openHours={}: {}", openHours, e.getMessage());
            return true;
        }
    }

    /**
     * 清理本配置过期的推送记录，默认空实现；子类可重写按各自去重/过期策略清理。
     * 在每次执行时（fetchStoreInfos 之前）调用，与是否命中无关。
     */
    protected void cleanupExpired(MonitorConfigEntity notifyConfig) {
        // 默认空
    }

    /**
     * 执行成功后的操作
     * @param notifyConfig
     */
    protected void afterSuccess(MonitorConfigEntity notifyConfig, List<StoreInfo> availableStores){
        //默认为空
    }

    /**
     * 监控命中后按配置自动建立抢单任务。对每个命中门店尝试建任务，
     * 单个失败不阻断主流程（通知已发出）。
     */
    private void triggerAutoGrab(MonitorConfigEntity notifyConfig, List<StoreInfo> availableStores) {
        if (!Boolean.TRUE.equals(notifyConfig.getAutoGrab())) return;
        for (StoreInfo store : availableStores) {
            try {
                autoGrabService.tryCreateFromMonitor(notifyConfig, store);
            } catch (Exception e) {
                log.warn("自动抢单建任务异常 configId={}, promotionId={}: {}",
                        notifyConfig.getId(), store.getPromotionId(), e.getMessage());
            }
        }
    }

    private void savePushedHistory(MonitorConfigEntity notifyConfig, List<StoreInfo> storeInfos){
        List<StorePushedHistoryEntity> entities = storeInfos.stream().map(storeInfo -> {
            StorePushedHistoryEntity entity = new StorePushedHistoryEntity();
            BeanUtils.copyProperties(storeInfo, entity);
            entity.setId(null);
            entity.setUserId(notifyConfig.getUserId());
            entity.setNotifyConfigId(notifyConfig.getId());
            entity.setNotifyType(notifyConfig.getType());
            return entity;
        }).toList();
        storePushedHistoryService.saveBatch(entities);
    }


    /**
     * 默认 body 模板
     */
    private static final String DEFAULT_BODY_TEMPLATE =
            "地点：${地点}<br/>" +
            "平台：${平台}<br/>" +
            "店铺：${店铺}<br/>" +
            "时间范围：${开始时间}-${结束时间}<br/>" +
            "距离：${距离}米<br/>" +
            "库存：${库存}<br/>" +
            "规则：满${满}返${返}<br/>" +
            "是否需要评价：${评价条件}";

    /**
     * 不同类型监控的默认 summary 模板
     */
    private static final String DEFAULT_SUMMARY_STORE_ACTIVITY = "${地点}: 指定门店有${数量}个新返现活动";
    private static final String DEFAULT_SUMMARY_STORE_KEYWORD = "${地点}: 关键字匹配到${数量}个新返现活动";
    private static final String DEFAULT_SUMMARY_MINIMUM_PAY = "${地点}: 最小实付匹配到${数量}个新返现活动";


    public void sendMessage(MonitorConfigEntity notifyConfig, List<StoreInfo> storeInfos, LocationEntity locationEntity) {
        String body = storeInfos.stream()
                .map(storeInfo -> buildMessage(storeInfo, locationEntity))
                .collect(Collectors.joining("<br/><br/>"));
        try {
            log.info("发送消息:{}", body);
            String summary = buildSummary(notifyConfig, storeInfos, locationEntity);
            pushService.pushToLocation(locationEntity.getId(), body, summary);
        }catch (Exception e){
            log.error("发送消息失败", e);
        }
    }

    private String buildSummary(MonitorConfigEntity notifyConfig, List<StoreInfo> storeInfos, LocationEntity locationEntity) {
        String summaryTemplate = switch (notifyConfig.getType()) {
            case STORE_ACTIVITY -> DEFAULT_SUMMARY_STORE_ACTIVITY;
            case STORE_KEYWORD -> DEFAULT_SUMMARY_STORE_KEYWORD;
            case MINIMUM_PAY -> DEFAULT_SUMMARY_MINIMUM_PAY;
        };
        return summaryTemplate
                .replace("${地点}", locationEntity.getName())
                .replace("${数量}", String.valueOf(storeInfos.size()))
                .replace("${类型}", notifyConfig.getType().getDescription());
    }

    private String buildMessage(StoreInfo storeInfo, LocationEntity locationEntity) {
        String rebateConditionText = storeInfo.getRebateCondition() == null ? "未知"
                : (storeInfo.getRebateCondition() != 99 ? "是" : "否");
        return BaseTask.DEFAULT_BODY_TEMPLATE
                .replace("${地点}", locationEntity.getName())
                .replace("${平台}", StorePlatformEnum.getByType(storeInfo.getType()).name)
                .replace("${店铺}", storeInfo.getName())
                .replace("${开始时间}", storeInfo.getStartTime())
                .replace("${结束时间}", storeInfo.getEndTime())
                .replace("${距离}", String.valueOf(storeInfo.getDistance()))
                .replace("${库存}", String.valueOf(storeInfo.getLeftNumber()))
                .replace("${满}", storeInfo.getPrice().toPlainString())
                .replace("${返}", storeInfo.getRebatePrice().toPlainString())
                .replace("${评价条件}", rebateConditionText);
    }
}
