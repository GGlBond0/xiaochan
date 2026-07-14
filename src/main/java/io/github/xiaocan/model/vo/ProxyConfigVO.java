package io.github.xiaocan.model.vo;

import lombok.Data;

/**
 * 代理配置返回
 */
@Data
public class ProxyConfigVO {

    /**
     * 是否启用代理
     */
    private Boolean enabled;

    /**
     * 代理 API 地址
     */
    private String apiUrl;

    /**
     * 代理缓存有效期(秒)
     */
    private Integer ttl;

    /**
     * 失败换代理重试次数
     */
    private Integer retry;

    /**
     * 上游请求超时(毫秒)
     */
    private Integer requestTimeout;
}
