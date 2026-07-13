package io.github.xiaocan.model;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门店关键字监控扩展配置
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StoreKeywordExtNotifyConfig extends AbstractExtNotifyConfig {

    /**
     * 门店关键字
     */
    @NotEmpty
    private String keyword;

    /**
     * 是否限制距离（超过3500米的门店过滤掉），默认true
     */
    private Boolean limitDistance = true;

    /**
     * 是否仅命中 3km 内（距离 <= 3000 米）的门店，默认 false。
     * 与 limitDistance 独立并存且为 AND 关系：勾选后实际生效半径即 3km。
     */
    private Boolean within3km = false;
}
