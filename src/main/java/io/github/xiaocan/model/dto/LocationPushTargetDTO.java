package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 地址推送目标 spt 新增/编辑
 */
@Data
public class LocationPushTargetDTO {
    /**
     * 编辑时传，新增不传
     */
    private Long id;
    /**
     * WxPusher spt（必填，新增/编辑均校验）
     */
    @NotBlank
    private String spt;
    /**
     * 备注
     */
    private String remark;
    /**
     * 启用 1 / 停用 0，默认启用
     */
    private Boolean enabled;
    /**
     * 排序，默认 0
     */
    private Integer sort;
}
