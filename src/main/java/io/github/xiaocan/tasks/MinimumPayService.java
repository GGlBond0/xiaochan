package io.github.xiaocan.tasks;

import com.alibaba.fastjson2.JSON;
import io.github.xiaocan.model.MinimumPayExtNotifyConfig;
import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.entity.TaskExecHistoryEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.enums.MonitorTypeEnums;
import io.github.xiaocan.service.MonitoryConfigService;
import io.github.xiaocan.service.StorePushedHistoryService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.service.XiaoChanService;
import io.github.xiaocan.model.entity.UserEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class MinimumPayService extends BaseTask {

    @Resource
    private XiaoChanService xiaoChanService;
    @Resource
    private MonitoryConfigService monitoryConfigService;
    @Resource
    private StorePushedHistoryService storePushedHistoryService;
    @Resource
    private UserService userService;

    /** 取该配置所属用户的全局去重/过期分钟数，null 兜底 60。 */
    private int dedupMinutesOf(MonitorConfigEntity notifyConfig) {
        UserEntity user = userService.getById(notifyConfig.getUserId());
        if (user == null || user.getNotifyDedupMinutes() == null) return 60;
        return user.getNotifyDedupMinutes();
    }



    @Override
    protected List<StoreInfo> fetchStoreInfos(MonitorConfigEntity notifyConfig, TaskExecHistoryEntity execHistory, LocationEntity location) {
        execHistory.setNotifyType(MonitorTypeEnums.MINIMUM_PAY);
        return xiaoChanService.getList(location.getCityCode(), location.getLongitude(), location.getLatitude(), 500);
    }

    @Override
    protected List<StoreInfo> filterStoreInfos(MonitorConfigEntity notifyConfig, List<StoreInfo> storeInfos) {
        MinimumPayExtNotifyConfig extNotifyConfig = JSON.parseObject(notifyConfig.getExtConfig(), MinimumPayExtNotifyConfig.class);
        int dedupMin = dedupMinutesOf(notifyConfig);
        return storeInfos
                .stream()
                .filter(storeInfo -> storeInfo.getLeftNumber() > 0)
                .filter(storeInfo -> storeInfo.getPrice().subtract(storeInfo.getRebatePrice()).compareTo(extNotifyConfig.getMinimumPay()) <= 0)
                // 仅命中 3km 内（距离 <= 3000 米）的门店，默认 false 不生效
                .filter(storeInfo -> !Boolean.TRUE.equals(extNotifyConfig.getWithin3km())
                        || (storeInfo.getDistance() != null && storeInfo.getDistance() <= 3000))
                // 去重：同店 N 分钟内已推送过则跳过（替代永久去重）
                .filter(storeInfo -> storePushedHistoryService.findByNotifyIdAndStoreIdWithinMinutes(notifyConfig.getId(), storeInfo.getStoreId(), dedupMin) == null)
                .toList();
    }

    @Override
    protected void cleanupExpired(MonitorConfigEntity notifyConfig) {
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



    /**
     * 最小实付（静态兜底调度，仅处理未配置 cron 的配置）
     */
    @Scheduled(cron = "0 45 * * * ? ")
    public void start(){
        try {
            //获取所有配置信息
            log.info("开始执行 最小实付活动 定时任务");
            List<MonitorConfigEntity> notifyConfigList = monitoryConfigService.listWithoutCron(MonitorTypeEnums.MINIMUM_PAY, MonitorConfigStatusEnums.ENABLE);
            for (MonitorConfigEntity notifyConfig : notifyConfigList) {
                execute(notifyConfig, false);
            }
        }catch (Exception e){
            log.error("发生异常 ", e);
        }
    }

    /**
     * 按配置执行任务入口
     *
     * @param cronDriven true 表示由 cron 动态调度器触发，跳过时间窗口和静默期检查
     */
    public void execute(MonitorConfigEntity notifyConfig, boolean cronDriven) {
        runSingle(notifyConfig, cronDriven);
    }
}
