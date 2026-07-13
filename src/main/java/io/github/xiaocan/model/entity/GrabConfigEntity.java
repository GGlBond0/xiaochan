package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单配置
 */
@Data
@TableName("grab_config")
public class GrabConfigEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 用户id
     */
    private Integer userId;
    /**
     * 绑定的登录态id（grab_login_state.id）
     */
    private Integer loginStateId;
    /**
     * 位置信息ID（为空则用入参经纬度）
     */
    private Long locationId;
    /**
     * 要抢的活动id（当天有效）
     */
    private Integer promotionId;
    /**
     * silk_id
     */
    private Integer silkId;
    /**
     * 平台，默认1
     */
    private Integer storePlatform;
    /**
     * 是否预售
     */
    private Boolean ifAdvanceOrder;
    /**
     * 定时抢单cron（6位含秒），空=仅手动/一次性
     */
    private String cron;
    /**
     * 一次性精确执行时间，命中后停用
     */
    private LocalDateTime executeAt;
    /**
     * 提前量（毫秒）
     */
    private Integer leadMs;
    /**
     * code4是否重试
     */
    private Boolean enableRetry;
    /**
     * 最大重试次数
     */
    private Integer maxRetry;
    /**
     * 重试间隔（毫秒）
     */
    private Integer retryIntervalMs;
    /**
     * 状态
     */
    private MonitorConfigStatusEnums status;
    /**
     * 最近一次结果
     */
    private String lastResult;
    /**
     * 最近抢单时间
     */
    private LocalDateTime lastGrabTime;
    /**
     * 抢到的订单id
     */
    private Long promotionOrderId;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}
