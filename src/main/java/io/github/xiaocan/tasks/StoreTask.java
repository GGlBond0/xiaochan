package io.github.xiaocan.tasks;

import com.alibaba.fastjson2.JSON;
import io.github.xiaocan.model.StoreExtNotifyConfig;
import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.StoreKeywordExtNotifyConfig;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.entity.StorePushedHistoryEntity;
import io.github.xiaocan.model.entity.TaskExecHistoryEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.enums.MonitorTypeEnums;
import io.github.xiaocan.http.MerchantBlacklistHolder;
import io.github.xiaocan.service.MonitoryConfigService;
import io.github.xiaocan.service.StorePushedHistoryService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.service.XiaoChanService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wangxiaodong
 * @date 2026/4/17
 */
@Component
@Slf4j
public class StoreTask extends BaseTask {


    @Resource
    private XiaoChanService xiaoChanService;
    @Resource
    private MonitoryConfigService monitoryConfigService;
    @Resource
    private StorePushedHistoryService storePushedHistoryService;
    @Resource
    private UserService userService;

    /** 取该配置所属用户的全局去重/过期分钟数，null 兜底 60。与 MinimumPayService 一致。 */
    private int dedupMinutesOf(MonitorConfigEntity notifyConfig) {
        UserEntity user = userService.getById(notifyConfig.getUserId());
        if (user == null || user.getNotifyDedupMinutes() == null) return 60;
        return user.getNotifyDedupMinutes();
    }

    /** 去重键：storeId + promotionId。promotionId 为 null 时占位 "null"，避免与有值活动混淆。 */
    private static String dedupKey(Integer storeId, Integer promotionId) {
        return storeId + ":" + (promotionId == null ? "null" : promotionId);
    }


    /**
     * 指定门店活动定时任务（静态兜底调度，仅处理未配置 cron 的配置）
     */
    @Scheduled(cron = "0 15 * * * ? ")
    public void start(){
        try {
            List<MonitorConfigEntity> all = monitoryConfigService.listWithoutCron(
                    List.of(MonitorTypeEnums.STORE_ACTIVITY, MonitorTypeEnums.STORE_KEYWORD), MonitorConfigStatusEnums.ENABLE);
            long storeActivityCount = all.stream().filter(c -> c.getType() == MonitorTypeEnums.STORE_ACTIVITY).count();
            long storeKeywordCount = all.stream().filter(c -> c.getType() == MonitorTypeEnums.STORE_KEYWORD).count();
            log.info("开始执行 门店活动定时任务 STORE_ACTIVITY:{}个，STORE_KEYWORD:{}个", storeActivityCount, storeKeywordCount);

            for (MonitorConfigEntity notifyConfig : all) {
                execute(notifyConfig, false);
            }
        }catch (Exception e){
            log.error("执行门店活动定时任务时发生异常", e);
        }
    }

    /**
     * 按配置执行任务入口
     *
     * @param cronDriven true 表示由 cron 动态调度器触发，跳过时间窗口和静默期检查
     */
    public void execute(MonitorConfigEntity notifyConfig, boolean cronDriven) {
        if (notifyConfig.getType() == MonitorTypeEnums.STORE_KEYWORD) {
            // STORE_KEYWORD：不做整体防重复，由 filterStoreInfos 内部按门店ID过滤
            runSingle(notifyConfig, cronDriven);
        } else {
            if (!checkRepeat(notifyConfig)) {
                runSingle(notifyConfig, cronDriven);
            }
        }
    }



    private boolean checkRepeat(MonitorConfigEntity notifyConfig) {
        //检查今天是否通知过了
        return storePushedHistoryService.lambdaQuery()
                .eq(StorePushedHistoryEntity::getNotifyConfigId, notifyConfig.getId())
                .ge(StorePushedHistoryEntity::getCreateTime, LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0))
                .last("limit 1")
                .oneOpt().isPresent();

    }

    /**
     * 获取门店活动信息
     */
    @Override
    protected List<StoreInfo> fetchStoreInfos(MonitorConfigEntity notifyConfig, TaskExecHistoryEntity execHistory, LocationEntity location) {
        String keyword;
        if (notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
            execHistory.setNotifyType(MonitorTypeEnums.STORE_ACTIVITY);
            StoreExtNotifyConfig storeExtNotifyConfig = JSON.parseObject(notifyConfig.getExtConfig(), StoreExtNotifyConfig.class);
            keyword = storeExtNotifyConfig.getStoreInfo().getName();
        } else {
            execHistory.setNotifyType(MonitorTypeEnums.STORE_KEYWORD);
            StoreKeywordExtNotifyConfig storeKeywordExtNotifyConfig = JSON.parseObject(notifyConfig.getExtConfig(), StoreKeywordExtNotifyConfig.class);
            keyword = storeKeywordExtNotifyConfig.getKeyword();
        }
        return xiaoChanService.searchList(keyword, location.getCityCode(), location.getLongitude(), location.getLatitude());
    }

    /**
     * 过滤指定门店活动
     */
    @Override
    protected List<StoreInfo> filterStoreInfos(MonitorConfigEntity notifyConfig, List<StoreInfo> storeInfos) {
        // 商家黑名单单点过滤：在 STORE_ACTIVITY / STORE_KEYWORD 两分支之前统一剔除命中门店
        storeInfos = storeInfos.stream().filter(s -> !MerchantBlacklistHolder.isBlacklisted(s.getName())).toList();
        if (notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
            StoreExtNotifyConfig storeExtNotifyConfig = JSON.parseObject(notifyConfig.getExtConfig(), StoreExtNotifyConfig.class);
            return storeInfos
                    .stream()
                    //同一个门店
                    .filter(storeInfo -> storeExtNotifyConfig.getStoreInfo().getStoreId().equals(storeInfo.getStoreId()))
                    .filter(storeInfo -> storeInfo.getLeftNumber() > 0)
                    //返现金额必须大于等于之前的返现金额
                    .filter(storeInfo -> storeInfo.getRebatePrice().compareTo(storeExtNotifyConfig.getStoreInfo().getRebatePrice()) >= 0)
                    //价格必须小于等于之前的价格
                    .filter(storeInfo -> storeInfo.getPrice().compareTo(storeExtNotifyConfig.getStoreInfo().getPrice()) <= 0)
                    .toList();
        } else {
            // STORE_KEYWORD：过滤有库存 + 距离限制 + 排除已通知过的门店
            StoreKeywordExtNotifyConfig storeKeywordExtNotifyConfig = JSON.parseObject(notifyConfig.getExtConfig(), StoreKeywordExtNotifyConfig.class);
            // 批量去重：一次取本配置最近 dedupMin 分钟内已推送的 (storeId, promotionId)，内存比对，消除逐店单查。
            // 同店同活动 N 分钟内不重复推；同店不同 promotionId 视为新活动不互相阻挡。
            int dedupMin = dedupMinutesOf(notifyConfig);
            Set<String> pushed = storePushedHistoryService.findPushedWithinMinutes(notifyConfig.getId(), dedupMin)
                    .stream()
                    .map(e -> dedupKey(e.getStoreId(), e.getPromotionId()))
                    .collect(Collectors.toSet());
            return storeInfos.stream()
                    .filter(storeInfo -> storeInfo.getLeftNumber() > 0)
                    .filter(storeInfo -> storeKeywordExtNotifyConfig.getLimitDistance() == null
                            || !storeKeywordExtNotifyConfig.getLimitDistance()
                            || (storeInfo.getDistance() != null && storeInfo.getDistance() <= 3500))
                    // 仅命中 3km 内（距离 <= 3000 米）的门店，默认 false 不生效；与 limitDistance 为 AND 关系
                    .filter(storeInfo -> !Boolean.TRUE.equals(storeKeywordExtNotifyConfig.getWithin3km())
                            || (storeInfo.getDistance() != null && storeInfo.getDistance() <= 3000))
                    .filter(storeInfo -> !pushed.contains(dedupKey(storeInfo.getStoreId(), storeInfo.getPromotionId())))
                    .toList();
        }
    }

    @Override
    protected void afterSuccess(MonitorConfigEntity notifyConfig, List<StoreInfo> availableStores) {
        super.afterSuccess(notifyConfig, availableStores);
        // 仅 STORE_ACTIVITY 通知后停用，STORE_KEYWORD 继续运行
        if (!availableStores.isEmpty() && notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
            monitoryConfigService.toggleStatus(notifyConfig.getId(), MonitorConfigStatusEnums.DISABLE);
        }
    }

    /**
     * 仅 STORE_KEYWORD 清理过期推送记录：删除本配置 N 分钟（用户全局去重分钟数）前的历史，
     * 无命中也每次执行都清，避免历史无限堆积，并使同店在过期后能再次被通知。
     * STORE_ACTIVITY 走当天去重（checkRepeat），其当天记录不应被 N 分钟清理误删，故提前 return。
     */
    @Override
    protected void cleanupExpired(MonitorConfigEntity notifyConfig) {
        if (notifyConfig.getType() != MonitorTypeEnums.STORE_KEYWORD) {
            return;
        }
        try {
            int dedupMin = dedupMinutesOf(notifyConfig);
            int deleted = storePushedHistoryService.deleteByNotifyIdOlderThanMinutes(notifyConfig.getId(), dedupMin);
            if (deleted > 0) {
                log.info("configId: {} 清理 {} 分钟前的过期推送记录 {} 条", notifyConfig.getId(), dedupMin, deleted);
            }
        } catch (Exception e) {
            log.warn("configId: {} 清理过期推送记录失败", notifyConfig.getId(), e);
        }
    }
}
