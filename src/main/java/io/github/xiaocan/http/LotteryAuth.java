package io.github.xiaocan.http;

import lombok.Builder;
import lombok.Data;

/**
 * 小蚕霸王餐刷任务 mini 登录态。
 * 来源：电脑微信小蚕惠生活小程序抓包，解析 X-Session-Id / x-Teemo(silk_id) / X-Vayne(user_id) / X-Nami。
 * 与抢单的 GrabAuth（Android 登录态，强依赖 X-Sivir JWT）分离，避免 isComplete 互扰。
 */
@Data
@Builder
public class LotteryAuth {
    /**
     * 小蚕 silk_id（请求 body + x-Teemo）
     */
    private Integer silkId;
    /**
     * 小蚕用户id（X-Vayne）
     */
    private Integer userVayne;
    /**
     * 会话id（X-Session-Id）
     */
    private String sessionId;
    /**
     * X-Nami（可选，为空则随机生成）
     */
    private String nami;

    /**
     * mini 登录态完整性校验：silkId + sessionId 必填（无 X-Sivir JWT）。
     */
    public boolean isComplete() {
        return silkId != null && sessionId != null && !sessionId.isEmpty();
    }
}
