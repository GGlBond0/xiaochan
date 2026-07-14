package io.github.xiaocan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.mapper.LocationPushTargetMapper;
import io.github.xiaocan.model.dto.LocationPushTargetDTO;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.LocationPushTargetEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.vo.LocationPushTargetVO;
import io.github.xiaocan.service.LocationPushTargetService;
import io.github.xiaocan.service.LocationService;
import io.github.xiaocan.service.PushService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@Slf4j
public class LocationPushTargetServiceImpl implements LocationPushTargetService {

    @Resource
    private LocationPushTargetMapper locationPushTargetMapper;
    @Resource
    private LocationService locationService;
    @Resource
    private UserService userService;
    @Resource
    private PushService pushService;

    @Override
    public List<LocationPushTargetVO> list(Long locationId) {
        assertLocationOwned(locationId);
        LambdaQueryWrapper<LocationPushTargetEntity> w = new LambdaQueryWrapper<>();
        w.eq(LocationPushTargetEntity::getLocationId, locationId)
                .orderByAsc(LocationPushTargetEntity::getSort)
                .orderByDesc(LocationPushTargetEntity::getId);
        return locationPushTargetMapper.selectList(w).stream()
                .map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long locationId, LocationPushTargetDTO dto) {
        assertLocationOwned(locationId);
        LocationPushTargetEntity e = new LocationPushTargetEntity();
        e.setLocationId(locationId);
        e.setSpt(dto.getSpt().trim());
        e.setRemark(dto.getRemark());
        e.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
        e.setIsDefault(Boolean.FALSE);
        e.setSort(dto.getSort() == null ? 0 : dto.getSort());
        locationPushTargetMapper.insert(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(LocationPushTargetDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("id 不能为空");
        }
        LocationPushTargetEntity e = locationPushTargetMapper.selectById(dto.getId());
        if (e == null) {
            throw new BusinessException("推送目标不存在");
        }
        assertLocationOwned(e.getLocationId());
        if (StringUtils.hasText(dto.getSpt())) {
            e.setSpt(dto.getSpt().trim());
        }
        e.setRemark(dto.getRemark());
        if (dto.getEnabled() != null) {
            e.setEnabled(dto.getEnabled());
        }
        if (dto.getSort() != null) {
            e.setSort(dto.getSort());
        }
        locationPushTargetMapper.updateById(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        LocationPushTargetEntity e = locationPushTargetMapper.selectById(id);
        if (e == null) {
            throw new BusinessException("推送目标不存在");
        }
        assertLocationOwned(e.getLocationId());
        locationPushTargetMapper.deleteById(id);
    }

    @Override
    public void testPush(Long locationId) {
        assertLocationOwned(locationId);
        pushService.testPush(locationId);
    }

    /** 校验地址存在且属于当前登录用户 */
    private void assertLocationOwned(Long locationId) {
        if (locationId == null) {
            throw new BusinessException("地址id不能为空");
        }
        LocationEntity loc = locationService.getById(locationId);
        if (loc == null) {
            throw new BusinessException("地址不存在");
        }
        UserEntity user = userService.getByCurrentRequest();
        if (!loc.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作该地址");
        }
    }

    private LocationPushTargetVO toVO(LocationPushTargetEntity e) {
        LocationPushTargetVO vo = new LocationPushTargetVO();
        vo.setId(e.getId());
        vo.setLocationId(e.getLocationId());
        vo.setSpt(e.getSpt());
        vo.setRemark(e.getRemark());
        vo.setEnabled(e.getEnabled());
        vo.setIsDefault(e.getIsDefault());
        vo.setSort(e.getSort());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }
}
