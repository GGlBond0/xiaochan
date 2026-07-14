package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小蚕霸王餐刷任务 mini 登录态（独立于 grab_login_state，不影响抢单）。
 * 来源：电脑微信小蚕惠生活小程序抓包，解析 X-Session-Id / x-Teemo(silk_id) / X-Vayne(user_id) / X-Nami。
 */
@Data
@TableName("lottery_auth")
public class LotteryAuthEntity {

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
     * 小蚕 silk_id（请求 body + X-Teemo）
     */
    private Integer silkId;
    /**
     * 小蚕用户id（X-Vayne）
     */
    private Integer userVayne;
    /**
     * 会话id（X-Session-Id）
     */
    private String sessionId;
    /**
     * X-Nami（可选，默认随机）
     */
    private String nami;
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
