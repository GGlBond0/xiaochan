package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 霸王餐刷任务 App(Android) 登录态（列表展示用，不返回 X-Sivir JWT）
 */
@Data
public class LotteryAuthVO {
    private Integer id;
    private String name;
    private Integer silkId;
    private Integer userVayne;
    private String sessionId;
    private Integer cityCode;
    private LocalDateTime updateTime;
}
