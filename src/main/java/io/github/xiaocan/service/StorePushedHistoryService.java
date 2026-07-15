package io.github.xiaocan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import io.github.xiaocan.model.dto.NotifyHistoryQueryDTO;
import io.github.xiaocan.model.entity.StorePushedHistoryEntity;
import io.github.xiaocan.model.vo.StorePushedHistoryVO;

import java.util.List;

public interface StorePushedHistoryService extends IService<StorePushedHistoryEntity> {

    /**
     * 分页查询通知历史记录（当前用户）
     * @param dto 分页参数
     * @return 分页结果
     */
    Page<StorePushedHistoryVO> pageByUser(NotifyHistoryQueryDTO dto);


    StorePushedHistoryEntity findByNotifyIdAndStoreIdToday(Integer notifyId, Integer storeId);

    StorePushedHistoryEntity findByNotifyIdAndStoreIdAll(Integer notifyId, Integer storeId);

    /**
     * 查询某监控配置在最近 minutes 分钟内已推送过的记录（仅取 storeId/promotionId 两列）。
     * 用于 MINIMUM_PAY 批量去重：调用方组装 (storeId, promotionId) 集合后内存比对，
     * 消除逐店单查的 N+1。配合覆盖索引 (notify_config_id, create_time, store_id, promotion_id) 走 index-only。
     */
    List<StorePushedHistoryEntity> findPushedWithinMinutes(Integer notifyId, int minutes);

    /**
     * 删除某监控配置下、创建时间早于 now-N 分钟的推送记录（物理删除）。
     * @return 删除的记录数
     */
    int deleteByNotifyIdOlderThanMinutes(Integer notifyId, int minutes);
}
