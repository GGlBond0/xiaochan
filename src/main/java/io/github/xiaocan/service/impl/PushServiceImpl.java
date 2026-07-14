package io.github.xiaocan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.xiaocan.http.MessageHttp;
import io.github.xiaocan.mapper.LocationPushTargetMapper;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.LocationPushTargetEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.service.LocationService;
import io.github.xiaocan.service.PushService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.utils.MaskUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class PushServiceImpl implements PushService {

    @Resource
    private LocationPushTargetMapper locationPushTargetMapper;
    @Resource
    private LocationService locationService;
    @Resource
    private UserService userService;

    @Override
    public List<String> getPushTargets(Long locationId) {
        if (locationId == null) {
            return List.of();
        }
        // 地址启用 spt（按 sort 升序），去重保序
        LambdaQueryWrapper<LocationPushTargetEntity> w = new LambdaQueryWrapper<>();
        w.eq(LocationPushTargetEntity::getLocationId, locationId)
                .eq(LocationPushTargetEntity::getEnabled, true)
                .orderByAsc(LocationPushTargetEntity::getSort);
        List<String> spts = locationPushTargetMapper.selectList(w).stream()
                .map(LocationPushTargetEntity::getSpt)
                .filter(StringUtils::hasText)
                .toList();
        Set<String> dedup = new LinkedHashSet<>(spts);
        if (!dedup.isEmpty()) {
            return List.copyOf(dedup);
        }
        // 无地址 spt 配置：回退地址所属用户的 user.spt
        LocationEntity loc = locationService.getById(locationId);
        if (loc != null && loc.getUserId() != null) {
            UserEntity user = userService.getById(loc.getUserId());
            if (user != null && StringUtils.hasText(user.getSpt())) {
                return List.of(user.getSpt());
            }
        }
        return List.of();
    }

    @Override
    public void pushToLocation(Long locationId, String content, String summary) {
        if (locationId == null) {
            log.warn("pushToLocation locationId 为空，跳过推送 summary={}", summary);
            return;
        }
        for (String spt : getPushTargets(locationId)) {
            sendOne(spt, content, summary);
        }
    }

    @Override
    public void pushToUser(Integer userId, String content, String summary) {
        if (userId == null) {
            return;
        }
        UserEntity user = userService.getById(userId);
        if (user == null || !StringUtils.hasText(user.getSpt())) {
            return;
        }
        sendOne(user.getSpt(), content, summary);
    }

    @Override
    public void testPush(Long locationId) {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId 不能为空");
        }
        LocationEntity loc = locationService.getById(locationId);
        String name = loc == null ? "" : loc.getName();
        String content = "【测试推送】地址「" + name + "」推送通道测试成功。";
        pushToLocation(locationId, content, "推送通道测试");
    }

    private void sendOne(String spt, String content, String summary) {
        try {
            MessageHttp.sendMessage(spt, content, summary);
        } catch (Exception e) {
            log.error("推送失败 spt={} summary={}", MaskUtil.mask(spt), summary, e);
        }
    }
}
