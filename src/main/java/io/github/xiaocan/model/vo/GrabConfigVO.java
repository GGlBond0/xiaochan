package io.github.xiaocan.model.vo;

import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单配置
 */
@Data
public class GrabConfigVO {
    private Integer id;
    private Integer loginStateId;
    private Long locationId;
    private Integer promotionId;
    private Integer silkId;
    private Integer storePlatform;
    private Boolean ifAdvanceOrder;
    private String cron;
    private LocalDateTime executeAt;
    private Integer leadMs;
    private Boolean enableRetry;
    private Integer maxRetry;
    private Integer retryIntervalMs;
    private MonitorConfigStatusEnums status;
    private String lastResult;
    private LocalDateTime lastGrabTime;
    private Long promotionOrderId;
    private LocalDateTime createTime;
}
