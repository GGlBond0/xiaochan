package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小蚕抢单登录态（一个系统用户可多组）
 */
@Data
@TableName("grab_login_state")
public class GrabLoginStateEntity {

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
     * 小蚕用户id（X-Vayne / JWT.UserId）
     */
    private Integer xcUserId;
    /**
     * 登录 JWT（X-Sivir）
     */
    private String xcSivir;
    /**
     * 会话id（X-Session-Id）
     */
    private String xcSessionId;
    /**
     * X-Nami（可选，默认随机）
     */
    private String xcNami;
    /**
     * silk_id（请求体 + X-Teemo）
     */
    private Integer silkId;
    /**
     * JWT 过期时间
     */
    private LocalDateTime expireAt;
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
