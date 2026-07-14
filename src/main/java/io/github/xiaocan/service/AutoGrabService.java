package io.github.xiaocan.service;

import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.entity.MonitorConfigEntity;

/**
 * 监控命中后自动建立抢单任务。
 * 监控执行体(StoreTask / MinimumPayService)命中门店活动时调用，
 * 按监控配置的 autoGrab 开关与绑定的登录态自动组装 grab_config 并注册调度。
 */
public interface AutoGrabService {

    /**
     * 监控命中后尝试自动建立抢单任务。
     *
     * @param config 命中的监控配置（含 autoGrab / grabLoginStateId / locationId / userId）
     * @param store  命中的门店活动（取 promotionId / startTime / endTime / type）
     * @return 建成的 grab_config.id；未建（未开启/非美团/防重/登录态过期/活动过期）返回 null
     */
    Long tryCreateFromMonitor(MonitorConfigEntity config, StoreInfo store);
}
