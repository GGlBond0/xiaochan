package io.github.xiaocan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.xiaocan.mapper.StorePushedHistoryMapper;
import io.github.xiaocan.model.dto.NotifyHistoryQueryDTO;
import io.github.xiaocan.model.entity.StorePushedHistoryEntity;
import io.github.xiaocan.model.vo.StorePushedHistoryVO;
import io.github.xiaocan.service.StorePushedHistoryService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.utils.PageConvertUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StorePushedHistoryServiceImpl extends ServiceImpl<StorePushedHistoryMapper, StorePushedHistoryEntity> implements StorePushedHistoryService {

    @Resource
    private UserService userService;

    @Override
    public Page<StorePushedHistoryVO> pageByUser(NotifyHistoryQueryDTO dto) {
        // 获取当前用户ID
        Integer userId = userService.getByCurrentRequest().getId();
        // 最近 N 分钟过滤：N 取当前用户的全局去重/过期分钟数（user.notify_dedup_minutes）
        Integer recentMinutes = userService.getByCurrentRequest().getNotifyDedupMinutes();
        boolean filterByMinutes = recentMinutes != null && recentMinutes > 0;
        LocalDateTime minCreateTime = filterByMinutes ? LocalDateTime.now().minusMinutes(recentMinutes) : null;

        // 使用lambdaQuery链式查询并转换为VO
        Page<StorePushedHistoryEntity> page = lambdaQuery()
                .eq(StorePushedHistoryEntity::getUserId, userId)
                .eq(dto.getNotifyConfigId() != null, StorePushedHistoryEntity::getNotifyConfigId, dto.getNotifyConfigId())
                .eq(dto.getNotifyType() != null, StorePushedHistoryEntity::getNotifyType, dto.getNotifyType())
                .ge(filterByMinutes, StorePushedHistoryEntity::getCreateTime, minCreateTime)
                .orderByDesc(StorePushedHistoryEntity::getId)
                .page(new Page<>(dto.getPageNum(), dto.getPageSize()));
        return PageConvertUtil.convert(page, StorePushedHistoryVO.class);
    }

    @Override
    public StorePushedHistoryEntity findByNotifyIdAndStoreIdToday(Integer notifyId, Integer storeId) {
        // 获取今天的开始时间和结束时间
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime todayEnd = todayStart.plusDays(1);

        return lambdaQuery()
                .eq(StorePushedHistoryEntity::getNotifyConfigId, notifyId)
                .eq(StorePushedHistoryEntity::getStoreId, storeId)
                .last("limit 1")
                .between(StorePushedHistoryEntity::getCreateTime, todayStart, todayEnd)
                .one();
    }


    @Override
    public StorePushedHistoryEntity findByNotifyIdAndStoreIdAll(Integer notifyId, Integer storeId) {
        return lambdaQuery()
                .eq(StorePushedHistoryEntity::getNotifyConfigId, notifyId)
                .eq(StorePushedHistoryEntity::getStoreId, storeId)
                .last("limit 1")
                .one();
    }

    @Override
    public List<StorePushedHistoryEntity> findPushedWithinMinutes(Integer notifyId, int minutes) {
        return lambdaQuery()
                .select(StorePushedHistoryEntity::getStoreId,
                        StorePushedHistoryEntity::getPromotionId)
                .eq(StorePushedHistoryEntity::getNotifyConfigId, notifyId)
                .ge(StorePushedHistoryEntity::getCreateTime, LocalDateTime.now().minusMinutes(minutes))
                .list();
    }

    @Override
    public int deleteByNotifyIdOlderThanMinutes(Integer notifyId, int minutes) {
        LambdaQueryWrapper<StorePushedHistoryEntity> wrapper = new LambdaQueryWrapper<StorePushedHistoryEntity>()
                .eq(StorePushedHistoryEntity::getNotifyConfigId, notifyId)
                .lt(StorePushedHistoryEntity::getCreateTime, LocalDateTime.now().minusMinutes(minutes));
        return getBaseMapper().delete(wrapper);
    }
}
