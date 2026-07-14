package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String token;

    private String spt;

    /**
     * 小蚕用户id（X-Teemo / X-Vayne）
     */
    private Integer xcUserId;
    /**
     * 登录 JWT（X-Sivir）
     */
    private String xcSivir;
    /**
     * 会话 id（X-Session-Id）
     */
    private String xcSessionId;
    /**
     * X-Nami（可选，默认随机生成）
     */
    private String xcNami;
    /**
     * 登录态录入时间
     */
    private java.time.LocalDateTime xcLoginUpdateTime;

    /**
     * 通知去重/过期分钟数（全局），默认 60。
     * 同店 N 分钟内不重复通知；超过 N 分钟的旧推送记录自动删除；
     * 通知记录页仅显示最近 N 分钟内的记录。
     */
    private Integer notifyDedupMinutes = 60;




}
