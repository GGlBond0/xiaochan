package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.GrabConfigDTO;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.vo.GrabCardCountVO;
import io.github.xiaocan.model.vo.GrabCardVO;
import io.github.xiaocan.model.vo.GrabConfigVO;
import io.github.xiaocan.model.vo.GrabHistoryVO;
import io.github.xiaocan.model.vo.GrabResultVO;
import io.github.xiaocan.service.GrabService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 抢单控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/grab")
public class GrabController {

    @Resource
    private GrabService grabService;

    /**
     * 保存/更新抢单配置
     */
    @PostMapping("/config")
    public BaseResult<Void> addUpdateConfig(@Valid @RequestBody GrabConfigDTO dto) {
        grabService.addUpdateConfig(dto);
        return BaseResult.ok();
    }

    /**
     * 抢单配置列表
     */
    @GetMapping("/config/list")
    public BaseResult<List<GrabConfigVO>> listConfig() {
        return BaseResult.ok(grabService.listByUserId());
    }

    /**
     * 删除抢单配置
     */
    @DeleteMapping("/config/{configId}")
    public BaseResult<Void> deleteConfig(@PathVariable Integer configId) {
        grabService.deleteById(configId);
        return BaseResult.ok();
    }

    /**
     * 启用/停用抢单配置
     */
    @PutMapping("/config/{configId}/status")
    public BaseResult<Void> toggleStatus(@PathVariable Integer configId,
                                          @RequestParam MonitorConfigStatusEnums status) {
        grabService.toggleStatus(configId, status);
        return BaseResult.ok();
    }

    /**
     * 手动立即抢一次
     */
    @PostMapping("/config/{configId}/execute")
    public BaseResult<GrabResultVO> executeManual(@PathVariable Integer configId) {
        return BaseResult.ok(grabService.executeManual(configId));
    }

    /**
     * 抢单历史记录
     */
    @GetMapping("/history/list")
    public BaseResult<List<GrabHistoryVO>> listHistory(@RequestParam(required = false, defaultValue = "50") Integer limit) {
        return BaseResult.ok(grabService.listHistoryByUserId(limit));
    }

    /**
     * 卡券查询：按登录态查该账号的卡券列表
     */
    @GetMapping("/card/list")
    public BaseResult<List<GrabCardVO>> listCards(
            @RequestParam Integer loginStateId,
            @RequestParam(required = false, defaultValue = "15") Integer number,
            @RequestParam(required = false, defaultValue = "0") Integer offset,
            @RequestParam(required = false, defaultValue = "0") Integer status) {
        return BaseResult.ok(grabService.listCards(loginStateId, number, offset, status));
    }

    /**
     * 卡券数量汇总：按登录态查该账号各类卡券数量（含饭票 cardId==1）
     */
    @GetMapping("/card/count")
    public BaseResult<GrabCardCountVO> countCards(@RequestParam Integer loginStateId) {
        return BaseResult.ok(grabService.countCards(loginStateId));
    }
}
