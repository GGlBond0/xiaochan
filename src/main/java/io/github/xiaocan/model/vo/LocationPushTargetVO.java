package io.github.xiaocan.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 地址推送目标 spt
 */
@Data
public class LocationPushTargetVO {
    private Long id;
    private Long locationId;
    private String spt;
    private String remark;
    private Boolean enabled;
    private Boolean isDefault;
    private Integer sort;
    private LocalDateTime createTime;
}
