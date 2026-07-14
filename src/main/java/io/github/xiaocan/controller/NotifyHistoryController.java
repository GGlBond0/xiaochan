package io.github.xiaocan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.NotifyHistoryQueryDTO;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.vo.StorePushedHistoryVO;
import io.github.xiaocan.service.StorePushedHistoryService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 通知历史记录接口
 */
@RestController
@RequestMapping(value = "/api/notify-history")
public class NotifyHistoryController {

    @Resource
    private StorePushedHistoryService notifyHistoryService;
    @Resource
    private UserService userService;


    /**
     * 分页查询通知历史记录（当前用户）
     */
    @PostMapping("/page")
    public BaseResult<Page<StorePushedHistoryVO>> page(@RequestBody NotifyHistoryQueryDTO dto) {
        return BaseResult.ok(notifyHistoryService.pageByUser(dto));
    }

    /**
     * 获取当前用户的全局去重/过期分钟数
     */
    @GetMapping("/dedup-minutes")
    public BaseResult<Integer> getDedupMinutes() {
        UserEntity user = userService.getByCurrentRequest();
        int minutes = user.getNotifyDedupMinutes() == null ? 60 : user.getNotifyDedupMinutes();
        return BaseResult.ok(minutes);
    }

    /**
     * 更新当前用户的全局去重/过期分钟数
     */
    @PutMapping("/dedup-minutes")
    public BaseResult<Void> updateDedupMinutes(@RequestBody Map<String, Object> body) {
        Object val = body.get("minutes");
        if (val == null) {
            return BaseResult.error("minutes 不能为空");
        }
        int minutes;
        try {
            minutes = Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return BaseResult.error("minutes 必须是整数");
        }
        if (minutes < 1) {
            return BaseResult.error("minutes 必须 >= 1");
        }
        UserEntity user = userService.getByCurrentRequest();
        UserEntity update = new UserEntity();
        update.setId(user.getId());
        update.setNotifyDedupMinutes(minutes);
        userService.updateById(update);
        return BaseResult.ok();
    }
}
