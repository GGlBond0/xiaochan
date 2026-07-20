package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.vo.LotteryDrawResultVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;
import io.github.xiaocan.service.LotteryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
     * 一键刷霸王餐浏览任务（完成未完成的浏览类任务，攒抽奖机会）。
     */
    @PostMapping("/run")
    public BaseResult<LotteryTaskResultVO> runTask(@RequestParam Integer authId) {
        return BaseResult.ok(lotteryService.runTask(authId));
    }

    /**
     * 开红包：用攒到的抽奖次数循环执行抽奖，直到抽完或失败。
     */
    @PostMapping("/draw")
    public BaseResult<LotteryDrawResultVO> draw(@RequestParam Integer authId) {
        return BaseResult.ok(lotteryService.draw(authId));
    }
}
