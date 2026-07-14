package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.LocationPushTargetDTO;
import io.github.xiaocan.model.vo.LocationPushTargetVO;
import io.github.xiaocan.service.LocationPushTargetService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 地址推送目标 spt 管理（一个地址可绑定多个 WxPusher spt）
 */
@RestController
@RequestMapping("/api/location/{locationId}/push-target")
public class LocationPushTargetController {

    @Resource
    private LocationPushTargetService locationPushTargetService;

    /**
     * 列出某地址下所有推送 spt
     */
    @GetMapping
    public BaseResult<List<LocationPushTargetVO>> list(@PathVariable Long locationId) {
        return BaseResult.ok(locationPushTargetService.list(locationId));
    }

    /**
     * 新增推送 spt（无验证码，直存）
     */
    @PostMapping
    public BaseResult<Void> add(@PathVariable Long locationId, @RequestBody @Valid LocationPushTargetDTO dto) {
        locationPushTargetService.add(locationId, dto);
        return BaseResult.ok();
    }

    /**
     * 编辑推送 spt（remark/enabled/sort/spt）
     */
    @PutMapping
    public BaseResult<Void> update(@RequestBody @Valid LocationPushTargetDTO dto) {
        locationPushTargetService.update(dto);
        return BaseResult.ok();
    }

    /**
     * 删除推送 spt
     */
    @DeleteMapping("/{id}")
    public BaseResult<Void> delete(@PathVariable Long locationId, @PathVariable Long id) {
        locationPushTargetService.delete(id);
        return BaseResult.ok();
    }

    /**
     * 测试推送：向该地址所有启用 spt 发一条测试消息
     */
    @PostMapping("/test")
    public BaseResult<Void> testPush(@PathVariable Long locationId) {
        locationPushTargetService.testPush(locationId);
        return BaseResult.ok();
    }
}
