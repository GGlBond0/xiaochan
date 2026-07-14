package io.github.xiaocan.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 地址推送目标（一个地址可绑定多个 WxPusher spt）
 */
@Data
@TableName("location_push_target")
public class LocationPushTargetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 地址id(location.id)
     */
    private Long locationId;

    /**
     * WxPusher spt
     */
    private String spt;

    /**
     * 备注
     */
    private String remark;

    /**
     * 1启用 0停用
     */
    private Boolean enabled;

    /**
     * 是否默认目标（预留，当前实现按 enabled 全推）
     */
    private Boolean isDefault;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    @TableLogic
    private Boolean deleted;
}
