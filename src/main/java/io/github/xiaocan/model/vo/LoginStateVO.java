package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小蚕 App 账号登录态统一池（列表展示用，不返回 X-Sivir JWT）。
 * 抢单 / 霸王餐刷任务 / 卡券查询共用。
 */
@Data
public class LoginStateVO {
    private Integer id;
    private String name;
    private Integer userVayne;
    private Integer silkId;
    private Integer cityCode;
    private LocalDateTime expireAt;
    private String expireStatus;
    private LocalDateTime updateTime;
    /**
     * 所属地址id(location.id)，可空
     */
    private Long locationId;
    /**
     * 所属地址名(分组展示用)，可空
     */
    private String locationName;
}
