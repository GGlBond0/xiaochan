package io.github.xiaocan.tasks;

import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.http.MessageHttp;
import io.github.xiaocan.mapper.GrabLoginStateMapper;
import io.github.xiaocan.model.entity.GrabLoginStateEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抢单登录态 JWT 过期检查：每天 9:07 扫描 grab_login_state，临过期/已过期推送提醒，按 id 去重。
 */
@Slf4j
@Component
public class GrabJwtExpireTask {

    /** 临过期阈值(秒)，默认 1 天 */
    private static final long EXPIRE_THRESHOLD_SECONDS = 1 * 24 * 3600L;

    @Resource
    private GrabLoginStateMapper grabLoginStateMapper;
    @Resource
    private UserService userService;

    /** 已提醒记录：loginStateId -> 已提醒的 expireAt，变化后重新提醒 */
    private final Map<Integer, LocalDateTime> reminded = new ConcurrentHashMap<>();

    @Scheduled(cron = "0 7 9 * * ?")
    public void checkJwtExpire() {
        List<GrabLoginStateEntity> list;
        try {
            list = grabLoginStateMapper.selectList(null);
        } catch (Exception e) {
            log.error("查询登录态列表失败", e);
            return;
        }
        if (list == null || list.isEmpty()) return;
        long nowSec = System.currentTimeMillis() / 1000;
        for (GrabLoginStateEntity state : list) {
            try {
                checkOne(state, nowSec);
            } catch (Exception e) {
                log.error("检查登录态 {} 过期失败", state.getId(), e);
            }
        }
    }

    private void checkOne(GrabLoginStateEntity state, long nowSec) {
        // 用记录里的 expireAt（录入时已解析），无则再解析
        Long exp = state.getExpireAt() == null ? parseJwtExp(state.getXcSivir())
                : state.getExpireAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        if (exp == null) return;
        UserEntity user = userService.getById(state.getUserId());
        if (user == null || !StringUtils.hasText(user.getSpt())) return;
        long remain = exp - nowSec;
        LocalDateTime expTime = java.time.Instant.ofEpochSecond(exp).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        if (remain > EXPIRE_THRESHOLD_SECONDS) {
            reminded.remove(state.getId());
            return;
        }
        if (reminded.get(state.getId()) != null && reminded.get(state.getId()).equals(expTime)) return;
        if (remain <= 0) {
            push(user, "小蚕登录态已过期", "抢单登录态「" + state.getName() + "」(JWT)已过期，请重新抓包录入，否则无法自动抢单。");
        } else {
            long days = remain / 86400;
            push(user, "小蚕登录态即将过期",
                    "抢单登录态「" + state.getName() + "」剩余约 " + days + " 天，过期时间 " + expTime
                            + "。请及时重新抓包录入，避免自动抢单失效。");
        }
        reminded.put(state.getId(), expTime);
    }

    private void push(UserEntity user, String summary, String content) {
        try {
            MessageHttp.sendMessage(user.getSpt(), content, summary);
            log.info("已推送 JWT 过期提醒 spt={}, summary={}", user.getSpt(), summary);
        } catch (Exception e) {
            log.error("推送 JWT 过期提醒失败", e);
        }
    }

    private Long parseJwtExp(String jwt) {
        if (!StringUtils.hasText(jwt)) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] d = Base64.getUrlDecoder().decode(parts[1]);
            JSONObject p = JSONObject.parse(new String(d, StandardCharsets.UTF_8));
            return p.getLong("exp");
        } catch (Exception e) {
            return null;
        }
    }
}
