package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 小蚕登录态录入/更新（粘贴抓包 header 原文）
 */
@Data
public class GrabLoginStateDTO {
    /**
     * 别名，如 主账号/小号（更新时可不传）
     */
    private String name;
    /**
     * 抓包请求头原文，多行 "Key: Value" 或抓包 JSON
     */
    @NotBlank
    private String rawHeaders;
}
