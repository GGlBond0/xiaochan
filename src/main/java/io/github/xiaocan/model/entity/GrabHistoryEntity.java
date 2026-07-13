package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单执行记录
 */
@Data
@TableName("grab_history")
public class GrabHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 用户id
     */
    private Integer userId;
    /**
     * 抢单配置id
     */
    private Integer grabConfigId;
    /**
     * 活动id
     */
    private Integer promotionId;
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    /**
     * 是否成功
     */
    private Boolean success;
    /**
     * 小蚕返回code（0成功,4未开始,6抢完）
     */
    private Integer respCode;
    /**
     * 小蚕返回msg
     */
    private String respMsg;
    /**
     * 抢到的订单id
     */
    private Long promotionOrderId;
    /**
     * 第几次重试
     */
    private Integer attempt;
    /**
     * 触发类型：MANUAL/CRON/ONESHOT
     */
    private String triggerType;
}
