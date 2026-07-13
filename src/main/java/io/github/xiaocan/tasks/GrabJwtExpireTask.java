package io.github.xiaocan.tasks;

import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.http.MessageHttp;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抢单登录态 JWT 过期检查：每天 9 点扫描，临过期(默认 1 天内)推送提醒，去重。
 */
@Slf4j
@Component
public class GrabJwtExpireTask {

    /** 临过期阈值(秒)，默认 1 天 */
    private static final long EXPIRE_THRESHOLD_SECONDS = 1 * 24 * 3600L;

    @Resource
    private UserService userService;

    /** 已提醒记录：userId -> 已提醒的 exp(秒)，exp 不变则不重复推 */
    private final Map<Integer, Long> reminded = new ConcurrentHashMap<>();

    /**
     * 每天 9:07 扫描一次（错峰，避开整点）
     */
    @Scheduled(cron = "0 7 9 * * ?")
    public void checkJwtExpire() {
        List<UserEntity> users;
        try {
            users = userService.list();
        } catch (Exception e) {
            log.error("查询用户列表失败", e);
            return;
        }
        if (users == null || users.isEmpty()) return;
        long nowSec = System.currentTimeMillis() / 1000;
        for (UserEntity user : users) {
            try {
                checkOne(user, nowSec);
            } catch (Exception e) {
                log.error("检查用户 {} 的 JWT 过期失败", user.getId(), e);
            }
        }
    }

    private void checkOne(UserEntity user, long nowSec) {
        if (!StringUtils.hasText(user.getXcSivir()) || !StringUtils.hasText(user.getSpt())) {
            return;
        }
        Long exp = parseJwtExp(user.getXcSivir());
        if (exp == null) return;
        long remain = exp - nowSec;
        if (remain > EXPIRE_THRESHOLD_SECONDS) {
            // 未到临过期窗口，清除已提醒标记
            reminded.remove(user.getId());
            return;
        }
        if (remain <= 0) {
            // 已过期：仅在未提醒过此 exp 时推送一次
            if (reminded.get(user.getId()) != null && reminded.get(user.getId()) == exp) return;
            push(user, "小蚕登录态已过期", "小蚕抢单登录态(JWT)已过期，请重新抓包录入，否则无法自动抢单。");
            reminded.put(user.getId(), exp);
            return;
        }
        // 临过期窗口内：同一 exp 只推一次
        if (reminded.get(user.getId()) != null && reminded.get(user.getId()) == exp) return;
        long days = remain / 86400;
        String expTime = new Date(exp * 1000).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
        push(user, "小蚕登录态即将过期",
                "小蚕抢单登录态(JWT)剩余约 " + days + " 天（" + LocalDateTime.now().toLocalDate() + " 查询），"
                        + "过期时间 " + expTime + "。请及时重新抓包录入，避免自动抢单失效。");
        reminded.put(user.getId(), exp);
    }

    private void push(UserEntity user, String summary, String content) {
        try {
            MessageHttp.sendMessage(user.getSpt(), content, summary);
            log.info("已推送 JWT 过期提醒 userId={}, summary={}", user.getId(), summary);
        } catch (Exception e) {
            log.error("推送 JWT 过期提醒失败 userId={}", user.getId(), e);
        }
    }

    /** 解析 JWT exp(秒)，失败返回 null */
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
