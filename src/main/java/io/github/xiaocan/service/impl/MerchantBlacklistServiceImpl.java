package io.github.xiaocan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.MerchantBlacklistHolder;
import io.github.xiaocan.mapper.MerchantBlacklistMapper;
import io.github.xiaocan.model.dto.MerchantBlacklistDTO;
import io.github.xiaocan.model.entity.MerchantBlacklistEntity;
import io.github.xiaocan.model.vo.MerchantBlacklistVO;
import io.github.xiaocan.service.MerchantBlacklistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家名称关键字黑名单全局配置服务实现。
 * 配置全局单份（约定 id=1），表空时以默认值（enabled=0, keywords=null）惰性初始化。
 * updateConfig 落库后调用 MerchantBlacklistHolder.invalidate() 使运行时缓存即时失效。
 */
@Service
@Slf4j
public class MerchantBlacklistServiceImpl extends ServiceImpl<MerchantBlacklistMapper, MerchantBlacklistEntity> implements MerchantBlacklistService {

    private static final int GLOBAL_ID = 1;

    @Override
    public MerchantBlacklistVO getConfig() {
        MerchantBlacklistEntity entity = ensureRow();
        MerchantBlacklistVO vo = new MerchantBlacklistVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(MerchantBlacklistDTO dto) {
        MerchantBlacklistEntity entity = ensureRow();
        entity.setEnabled(dto.getEnabled());
        entity.setKeywords(dto.getKeywords());
        boolean ok = this.updateById(entity);
        if (!ok) {
            throw new BusinessException("更新商家黑名单配置失败");
        }
        // 保存后让 MerchantBlacklistHolder 运行时缓存即时失效，下次判断即用新规则，无需重启
        MerchantBlacklistHolder.invalidate();
        log.info("商家黑名单配置已更新: enabled={}", entity.getEnabled());
    }

    @Override
    public MerchantBlacklistEntity getEntity() {
        return ensureRow();
    }

    /**
     * 确保全局配置行存在（id=1）；表空则以默认值（enabled=0, keywords=null）惰性初始化。
     * synchronized 防止并发首初始化各写一行。
     */
    private synchronized MerchantBlacklistEntity ensureRow() {
        MerchantBlacklistEntity entity = this.getById(GLOBAL_ID);
        if (entity != null) {
            return entity;
        }
        log.info("merchant_blacklist_config 表为空，以默认值初始化全局配置行");
        entity = new MerchantBlacklistEntity();
        entity.setId(GLOBAL_ID);
        entity.setEnabled(false);
        entity.setKeywords(null);
        this.save(entity);
        return this.getById(GLOBAL_ID);
    }
}
