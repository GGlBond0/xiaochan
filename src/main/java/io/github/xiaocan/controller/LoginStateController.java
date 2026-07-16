package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.LoginStateDTO;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.vo.LoginStateVO;
import io.github.xiaocan.service.LoginStateService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 小蚕 App 账号登录态统一管理（抢单 / 霸王餐刷任务 / 卡券查询共用）。
 */
@Slf4j
@RestController
@RequestMapping("/api/login-state")
public class LoginStateController {

    @Resource
    private LoginStateService loginStateService;

    /**
     * 新增/更新登录态（粘贴抓包 header 原文）。id 为空则新增，否则更新。
     */
    @PostMapping
    public BaseResult<LoginStateVO> save(@Valid @RequestBody LoginStateDTO dto,
                                          @RequestParam(required = false) Integer id) {
        LoginStateEntity entity = loginStateService.save(dto, id);
        // 返回最新列表项（重新拉一次该 id 的展示信息）
        LoginStateVO vo = loginStateService.list().stream()
                .filter(v -> v.getId().equals(entity.getId())).findFirst().orElse(null);
        return BaseResult.ok(vo);
    }

    /**
     * 登录态列表（当前用户多组）
     */
    @GetMapping("/list")
    public BaseResult<List<LoginStateVO>> list() {
        return BaseResult.ok(loginStateService.list());
    }

    /**
     * 删除登录态
     */
    @DeleteMapping("/{id}")
    public BaseResult<Void> delete(@PathVariable Integer id) {
        loginStateService.delete(id);
        return BaseResult.ok();
    }

    /**
     * 绑定/解绑登录态到地址。只改所属地址，不改登录态明文。
     * @param id 登录态 id
     * @param locationId 地址 id；不传或传 null 表示解绑
     */
    @PutMapping("/{id}/location")
    public BaseResult<Void> bindLocation(@PathVariable Integer id,
                                          @RequestParam(required = false) Long locationId) {
        loginStateService.bindLocation(id, locationId);
        return BaseResult.ok();
    }
}
