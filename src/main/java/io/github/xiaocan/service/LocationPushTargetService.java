package io.github.xiaocan.service;

import io.github.xiaocan.model.dto.LocationPushTargetDTO;
import io.github.xiaocan.model.vo.LocationPushTargetVO;

import java.util.List;

/**
 * 地址推送目标 spt 管理
 */
public interface LocationPushTargetService {

    /** 列出某地址下所有推送 spt（校验地址属当前用户） */
    List<LocationPushTargetVO> list(Long locationId);

    /** 新增（locationId 属当前用户校验） */
    void add(Long locationId, LocationPushTargetDTO dto);

    /** 编辑（remark/enabled/sort/spt，校验属当前用户） */
    void update(LocationPushTargetDTO dto);

    /** 删除（校验属当前用户） */
    void delete(Long id);

    /** 测试推送：向该地址所有启用 spt 发一条测试消息 */
    void testPush(Long locationId);
}
