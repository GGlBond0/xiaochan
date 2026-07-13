package io.github.xiaocan.model.dto;

import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单配置
 */
@Data
public class GrabConfigDTO {
    private Integer id;
    /**
     * 绑定的登录态id（grab_login_state.id）
     */
    @NotNull
    private Integer loginStateId;
    /**
     * 位置信息ID（用于取经纬度/city_code，与监控配置一致）
     */
    @NotNull
    private Long locationId;
    /**
     * 要抢的活动id（当天有效）
     */
    @NotNull
    private Integer promotionId;
    /**
     * silk_id
     */
    private Integer silkId;
    private Integer storePlatform;
    private Boolean ifAdvanceOrder;
    /**
     * 定时抢单cron（6位含秒），空=仅手动/一次性
     */
    private String cron;
    /**
     * 一次性精确执行时间
     */
    private LocalDateTime executeAt;
    /**
     * 提前量（毫秒）
     */
    private Integer leadMs;
    private Boolean enableRetry;
    private Integer maxRetry;
    private Integer retryIntervalMs;
    private MonitorConfigStatusEnums status;
}
