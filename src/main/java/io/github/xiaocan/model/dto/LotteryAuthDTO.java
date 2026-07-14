package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 霸王餐刷任务 mini 登录态录入/更新（粘贴抓包 header 原文）。
 * 与抢单 GrabLoginStateDTO 分离：mini 登录态无 X-Sivir，只需 X-Session-Id + silk_id。
 */
@Data
public class LotteryAuthDTO {
    /**
     * 别名，如 主账号/小号（更新时可不传）
     */
    private String name;
    /**
     * 抓包请求头原文，多行 "Key: Value" 或抓包 JSON。
     * 必须含 X-Session-Id 与 x-Teemo（或 body.silk_id）。
     */
    @NotBlank
    private String rawHeaders;
}
