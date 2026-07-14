package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小蚕 App 账号登录态统一池（一个系统用户可多组）。
 * 抢单 / 霸王餐刷任务 / 卡券查询共用；历史上拆成 grab_login_state 与 lottery_auth 两表，已合并。
 */
@Data
@TableName("login_state")
public class LoginStateEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 系统用户id
     */
    private Integer userId;
    /**
     * 别名
     */
    private String name;
    /**
     * X-Sivir JWT（登录态，必填）
     */
    private String sivir;
    /**
     * X-Session-Id
     */
    private String sessionId;
    /**
     * 小蚕用户id（X-Vayne / JWT.UserId）
     */
    private Integer userVayne;
    /**
     * silk_id（请求体 + X-Teemo）
     */
    private Integer silkId;
    /**
     * X-Nami（可选，默认随机）
     */
    private String nami;
    /**
     * x-City 城市码（霸王餐用，可空）
     */
    private Integer cityCode;
    /**
     * 所属地址id（抢单可选绑定，老记录/霸王餐留空）
     */
    private Long locationId;
    /**
     * JWT 过期时间（解析 exp）
     */
    private LocalDateTime expireAt;
    /**
     * 录入的原始抓包 header（留底）
     */
    private String rawHeaders;
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
