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
}
