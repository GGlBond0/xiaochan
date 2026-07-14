package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.LotteryAuthDTO;
import io.github.xiaocan.model.vo.LotteryAuthVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;
import io.github.xiaocan.service.LotteryService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 霸王餐刷浏览任务控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/lottery")
public class LotteryController {

    @Resource
    private LotteryService lotteryService;

    /**
     * 新增/更新 mini 登录态（粘贴抓包 header 原文）。id 为空则新增，返回 id。
     */
    @PostMapping("/auth")
    public BaseResult<Integer> saveAuth(@Valid @RequestBody LotteryAuthDTO dto,
                                        @RequestParam(required = false) Integer id) {
        return BaseResult.ok(lotteryService.saveAuth(dto, id));
    }

    /**
     * 登录态列表
     */
    @GetMapping("/auth/list")
    public BaseResult<List<LotteryAuthVO>> listAuth() {
        return BaseResult.ok(lotteryService.listAuth());
    }

    /**
     * 删除登录态
     */
    @DeleteMapping("/auth/{id}")
    public BaseResult<Void> deleteAuth(@PathVariable Integer id) {
        lotteryService.deleteAuth(id);
        return BaseResult.ok();
    }

    /**
     * 一键刷霸王餐浏览任务（完成未完成的浏览类任务，攒抽奖机会）。
     */
    @PostMapping("/run")
    public BaseResult<LotteryTaskResultVO> runTask(@RequestParam Integer authId) {
        return BaseResult.ok(lotteryService.runTask(authId));
    }
}
