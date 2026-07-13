package io.github.xiaocan.http;

import io.github.xiaocan.model.entity.GrabLoginStateEntity;
import lombok.Builder;
import lombok.Data;

/**
 * 抢单登录态。X-Nami 为空时由 {@link XiaochanHttp#getNami()} 随机生成。
 * x-Teemo = silk_id，X-Vayne = 用户id（见抓包 favorites1.json）。
 */
@Data
@Builder
public class GrabAuth {
    /**
     * X-Sivir 登录 JWT
     */
    private String sivir;
    /**
     * 小蚕用户id（X-Vayne / JWT.UserId）
     */
    private Integer userId;
    /**
     * X-Session-Id
     */
    private String sessionId;
    /**
     * X-Nami（可选，默认随机）
     */
    private String nami;
    /**
     * silk_id（X-Teemo / 请求体 silk_id）
     */
    private Integer silkId;

    public static GrabAuth from(GrabLoginStateEntity state) {
        if (state == null) {
            return null;
        }
        return GrabAuth.builder()
                .sivir(state.getXcSivir())
                .userId(state.getXcUserId())
                .sessionId(state.getXcSessionId())
                .nami(state.getXcNami())
                .silkId(state.getSilkId())
                .build();
    }

    /**
     * 登录态是否完整可用
     */
    public boolean isComplete() {
        return sivir != null && !sivir.isEmpty()
                && userId != null
                && sessionId != null && !sessionId.isEmpty();
    }
}

