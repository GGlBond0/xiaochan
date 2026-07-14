package io.github.xiaocan.http;

import lombok.Builder;
import lombok.Data;

/**
 * 小蚕霸王餐刷任务 App(Android) 登录态。
 * 来源：小蚕 App 抓包，解析 X-Session-Id / x-Teemo(silk_id) / X-Vayne(user_id) / X-Sivir(JWT) / x-City。
 * 与抢单的 GrabAuth 物理隔离（各自独立表），但同为 Android 登录态来源，方便项目其它部分复用。
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
     * X-Sivir JWT（Android 登录态，必填）
     */
    private String sivir;
    /**
     * x-City 城市码（如 440111，可为空）
     */
    private Integer cityCode;

    /**
     * App 登录态完整性校验：silkId + sessionId + sivir 必填（带 X-Sivir JWT）。
     */
    public boolean isComplete() {
        return silkId != null
                && sessionId != null && !sessionId.isEmpty()
                && sivir != null && !sivir.isEmpty();
    }
}
