package io.github.xiaocan.model.vo;

import lombok.Data;

/**
 * 抢单结果
 */
@Data
public class GrabResultVO {
    /**
     * 小蚕返回code（0成功）
     */
    private Integer code;
    /**
     * 小蚕返回msg
     */
    private String msg;
    /**
     * 抢到的订单id（code=0）
     */
    private Long promotionOrderId;
    /**
     * 是否成功
     */
    private Boolean success;
}
