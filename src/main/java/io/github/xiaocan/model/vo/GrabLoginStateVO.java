package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抢单登录态
 */
@Data
public class GrabLoginStateVO {
    private Integer id;
    private String name;
    private Integer xcUserId;
    private Integer silkId;
    private LocalDateTime expireAt;
    private String expireStatus;
    private LocalDateTime updateTime;
    /**
     * 所属地址id(location.id)，可空
     */
    private Long locationId;
    /**
     * 所属地址名(地址页分组展示用)，可空
     */
    private String locationName;
}
