package io.github.xiaocan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.ProxyHolder;
import io.github.xiaocan.mapper.ProxyConfigMapper;
import io.github.xiaocan.model.dto.ProxyConfigDTO;
import io.github.xiaocan.model.entity.ProxyConfigEntity;
import io.github.xiaocan.model.vo.ProxyConfigVO;
import io.github.xiaocan.service.ProxyConfigService;
import io.github.xiaocan.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代理IP池全局配置服务实现。
 * 配置全局单份（约定 id=1），表空时用环境变量默认值惰性初始化。
 * updateConfig 落库后调用 ProxyHolder.invalidate() 使运行时缓存即时失效。
 */
@Service
@Slf4j
public class ProxyConfigServiceImpl extends ServiceImpl<ProxyConfigMapper, ProxyConfigEntity> implements ProxyConfigService {

    private static final int GLOBAL_ID = 1;

    @Override
    public ProxyConfigVO getConfig() {
        ProxyConfigEntity entity = ensureRow();
        ProxyConfigVO vo = new ProxyConfigVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(ProxyConfigDTO dto) {
        if (Boolean.TRUE.equals(dto.getEnabled()) && (dto.getApiUrl() == null || dto.getApiUrl().trim().isEmpty())) {
            throw new BusinessException("启用代理时 API 地址必填");
        }
        ProxyConfigEntity entity = ensureRow();
        entity.setEnabled(dto.getEnabled());
        entity.setApiUrl(dto.getApiUrl());
        if (dto.getTtl() != null) {
            entity.setTtl(dto.getTtl());
        }
        if (dto.getRetry() != null) {
            entity.setRetry(dto.getRetry());
        }
        if (dto.getRequestTimeout() != null) {
            entity.setRequestTimeout(dto.getRequestTimeout());
        }
        boolean ok = this.updateById(entity);
        if (!ok) {
            throw new BusinessException("更新代理配置失败");
        }
        // 保存后让 ProxyHolder 运行时缓存即时失效，下次取代理即用新配置，无需重启
        ProxyHolder.invalidate();
        log.info("代理配置已更新: enabled={}, ttl={}, retry={}, timeout={}",
                entity.getEnabled(), entity.getTtl(), entity.getRetry(), entity.getRequestTimeout());
    }

    @Override
    public ProxyConfigEntity getEntity() {
        return ensureRow();
    }

    /**
     * 确保全局配置行存在（id=1）；表空则用环境变量默认值惰性初始化。
     * synchronized 防止并发首初始化各写一行。
     */
    private synchronized ProxyConfigEntity ensureRow() {
        ProxyConfigEntity entity = this.getById(GLOBAL_ID);
        if (entity != null) {
            return entity;
        }
        log.info("proxy_config 表为空，用环境变量默认值初始化全局配置行");
        entity = new ProxyConfigEntity();
        entity.setId(GLOBAL_ID);
        entity.setEnabled("true".equalsIgnoreCase(env("PROXY_ENABLED", "false")));
        entity.setApiUrl(env("PROXY_API_URL", ""));
        entity.setTtl(parseIntOrDefault("PROXY_TTL", 28));
        entity.setRetry(parseIntOrDefault("PROXY_RETRY", 3));
        entity.setRequestTimeout(parseIntOrDefault("PROXY_REQUEST_TIMEOUT", 5000));
        this.save(entity);
        return this.getById(GLOBAL_ID);
    }

    private String env(String key, String def) {
        try {
            String v = SpringContextUtil.getApplicationContext().getEnvironment().getProperty(key);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private int parseIntOrDefault(String key, int def) {
        try {
            String v = env(key, null);
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
