package io.github.xiaocan.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 小蚕 App 账号登录态录入/更新（粘贴抓包 header 原文）。
 * 抢单 / 霸王餐刷任务共用。必须含 X-Sivir 与 X-Session-Id；抢单/卡券还需 silk_id(X-Teemo 或 body.silk_id)。
 */
@Data
public class LoginStateDTO {
    /**
     * 别名，如 主账号/小号（更新时可不传）
     */
    private String name;
    /**
     * 所属地址id(location.id)，可空。抢单在地址页录入时填该地址；霸王餐不绑地址留空。
     */
    private Long locationId;
    /**
     * 抓包请求头原文，多行 "Key: Value" 或抓包 JSON（含 headers/body 节点）。
     */
    @NotBlank
    private String rawHeaders;
}
