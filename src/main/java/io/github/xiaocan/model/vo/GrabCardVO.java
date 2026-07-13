package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小蚕卡券
 */
@Data
public class GrabCardVO {
    /**
     * 卡券领取记录id
     */
    private Long id;
    /**
     * 卡券定义id
     */
    private Integer cardId;
    /**
     * 卡券类型
     */
    private Integer cardType;
    /**
     * 卡券名称
     */
    private String name;
    /**
     * 卡券描述
     */
    private String desc;
    /**
     * 图片
     */
    private String pic;
    /**
     * 过期时间
     */
    private LocalDateTime expireTime;
    /**
     * 领取时间
     */
    private LocalDateTime createdAt;
}
