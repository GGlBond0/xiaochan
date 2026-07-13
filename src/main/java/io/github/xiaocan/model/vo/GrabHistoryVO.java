package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单执行记录
 */
@Data
public class GrabHistoryVO {
    private Integer id;
    private Integer grabConfigId;
    private Integer promotionId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean success;
    private Integer respCode;
    private String respMsg;
    private Long promotionOrderId;
    private Integer attempt;
    private String triggerType;
}
