package io.github.xiaocan.controller;

import io.github.xiaocan.model.BaseResult;
import io.github.xiaocan.model.dto.ProxyConfigDTO;
import io.github.xiaocan.model.vo.ProxyConfigVO;
import io.github.xiaocan.service.ProxyConfigService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 代理IP池全局配置读写接口。
 * 全局单份配置，所有登录用户均可读可改。
 */
@RestController
@RequestMapping(value = "/api/proxy")
public class ProxyConfigController {

    @Resource
    private ProxyConfigService proxyConfigService;
    @Resource
    private UserService userService;

    /**
     * 读取全局代理配置
     */
    @GetMapping("/config")
    public BaseResult<ProxyConfigVO> get() {
        userService.getByCurrentRequest();
        return BaseResult.ok(proxyConfigService.getConfig());
    }

    /**
     * 更新全局代理配置（保存后即时生效，无需重启）
     */
    @PutMapping("/config")
    public BaseResult<Void> update(@Valid @RequestBody ProxyConfigDTO dto) {
        userService.getByCurrentRequest();
        proxyConfigService.updateConfig(dto);
        return BaseResult.ok();
    }
}
