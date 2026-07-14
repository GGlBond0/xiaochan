package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 代理配置入参
 */
@Data
public class ProxyConfigDTO {

    /**
     * 是否启用代理
     */
    private Boolean enabled;

    /**
     * 代理 API 地址（enabled=true 时必填，service 层校验）
     */
    private String apiUrl;

    /**
     * 代理缓存有效期(秒)
     */
    @Min(1)
    private Integer ttl;

    /**
     * 失败换代理重试次数
     */
    @Min(1)
    private Integer retry;

    /**
     * 上游请求超时(毫秒)
     */
    @Min(1000)
    private Integer requestTimeout;
}
