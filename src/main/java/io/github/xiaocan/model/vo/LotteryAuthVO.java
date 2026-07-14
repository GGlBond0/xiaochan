package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 霸王餐刷任务 mini 登录态（列表展示用）
 */
@Data
public class LotteryAuthVO {
    private Integer id;
    private String name;
    private Integer silkId;
    private Integer userVayne;
    private String sessionId;
    private LocalDateTime updateTime;
}
