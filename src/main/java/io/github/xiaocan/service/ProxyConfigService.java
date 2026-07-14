package io.github.xiaocan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.xiaocan.model.dto.ProxyConfigDTO;
import io.github.xiaocan.model.entity.ProxyConfigEntity;
import io.github.xiaocan.model.vo.ProxyConfigVO;

/**
 * 代理IP池全局配置服务
 */
public interface ProxyConfigService extends IService<ProxyConfigEntity> {

    /**
     * 读取全局代理配置（表空则用环境变量默认值初始化一行）
     */
    ProxyConfigVO getConfig();

    /**
     * 更新全局代理配置，保存后使 ProxyHolder 缓存失效（即时生效）
     */
    void updateConfig(ProxyConfigDTO dto);

    /**
     * 供 ProxyHolder 运行时读取原始配置，永不返回 null
     */
    ProxyConfigEntity getEntity();
}
