package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.GrabAuth;
import io.github.xiaocan.http.LotteryAuth;
import io.github.xiaocan.mapper.LoginStateMapper;
import io.github.xiaocan.model.dto.LoginStateDTO;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.vo.LoginStateVO;
import io.github.xiaocan.service.LocationService;
import io.github.xiaocan.service.LoginStateService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小蚕 App 账号登录态统一服务实现。
 * 合并原 GrabServiceImpl 与 LotteryServiceImpl 两处重复的抓包 header 解析逻辑为单池。
 */
@Slf4j
@Service
public class LoginStateServiceImpl implements LoginStateService {

    /** 抓包 header 行解析：Key: Value（含大小写变体如 X-Sivir / x-Teemo） */
    private static final Pattern HEADER_LINE =
            Pattern.compile("(?i)^\\s*\"?([A-Za-z-]+)\"?\\s*[:：]\\s*\"?(.*?)\"?\\s*$");

    @Resource
    private UserService userService;
    @Resource
    private LocationService locationService;
    @Resource
    private LoginStateMapper loginStateMapper;

    /** 抓包解析中间结构 */
    private static final class Parsed {
        String sivir;
        String sessionId;
        String nami;
        String vayne;
        String teemo;
        Integer cityCode;
        Integer bodySilkId;
        Long exp;
        Integer jwtUserId;
    }

    // ==================== 解析（抢单 + 霸王餐共用） ====================

    private Parsed parseRawHeaders(String raw) {
        Parsed p = new Parsed();
        if (raw == null) return p;
        // body.silk_id 必须从原始 raw 解析（含抓包 JSON 的 body 节点），
        // 否则下面 headers 节点提取后 raw 被替换为 "k: v" 行，不再以 { 开头，body 解析会失效。
        p.bodySilkId = parseSilkIdFromBody(raw);
        // 兼容抓包 JSON：含 headers / body 节点时取出 headers 行拼接
        if (raw.trim().startsWith("{")) {
            try {
                JSONObject json = JSONObject.parseObject(raw);
                JSONObject headers = json.getJSONObject("headers");
                if (headers != null) {
                    raw = headers.entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                }
            } catch (Exception ignore) { }
        }
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = HEADER_LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase();
            String val = m.group(2).trim();
            if (val.startsWith("[")) {
                val = val.replaceAll("[\\[\\]\"\\\\]", "");
            }
            switch (key) {
                case "x-sivir" -> p.sivir = val;
                case "x-session-id" -> p.sessionId = val;
                case "x-nami" -> p.nami = val;
                case "x-vayne" -> p.vayne = val;
                case "x-teemo" -> p.teemo = val;
                case "x-city", "x-citycode" -> {
                    if (p.cityCode == null && StringUtils.hasText(val)) {
                        try { p.cityCode = Integer.parseInt(val); } catch (Exception ignore) { }
                    }
                }
                default -> { }
            }
        }
        if (StringUtils.hasText(p.sivir)) {
            p.exp = parseJwtExp(p.sivir);
            p.jwtUserId = parseJwtUserId(p.sivir);
        }
        return p;
    }

    /** 从抓包 JSON 的 body 节点解析 silk_id（兼容 favorites1.json） */
    private Integer parseSilkIdFromBody(String raw) {
        if (raw == null || !raw.trim().startsWith("{")) return null;
        try {
            JSONObject json = JSONObject.parseObject(raw);
            JSONObject body = json.getJSONObject("body");
            if (body != null) return body.getInteger("silk_id");
        } catch (Exception ignore) { }
        return null;
    }

    private Integer resolveUserVayne(Parsed p) {
        if (StringUtils.hasText(p.vayne)) {
            try { return Integer.parseInt(p.vayne); } catch (Exception ignore) { }
        }
        return p.jwtUserId;
    }

    private Integer resolveSilkId(Parsed p) {
        Integer silkId = p.bodySilkId;
        if (silkId == null && StringUtils.hasText(p.teemo)) {
            try { silkId = Integer.parseInt(p.teemo); } catch (Exception ignore) { }
        }
        return silkId;
    }

    private Long parseJwtExp(String jwt) {
        JSONObject payload = parseJwtPayload(jwt);
        return payload == null ? null : payload.getLong("exp");
    }

    private Integer parseJwtUserId(String jwt) {
        JSONObject payload = parseJwtPayload(jwt);
        return payload == null ? null : payload.getInteger("UserId");
    }

    private JSONObject parseJwtPayload(String jwt) {
        if (!StringUtils.hasText(jwt)) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] d = Base64.getUrlDecoder().decode(parts[1]);
            return JSONObject.parse(new String(d, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== CRUD ====================

    @Override
    public LoginStateEntity save(LoginStateDTO dto, Integer id) {
        UserEntity user = userService.getByCurrentRequest();
        Parsed p = parseRawHeaders(dto.getRawHeaders());
        // 最低校验：X-Sivir 与 X-Session-Id 都必须有（抢单/霸王餐共同要求）
        if (!StringUtils.hasText(p.sivir)) {
            throw new BusinessException("未解析到登录态：缺少 X-Sivir（App 登录态 JWT）");
        }
        if (!StringUtils.hasText(p.sessionId)) {
            throw new BusinessException("未解析到登录态：缺少 X-Session-Id");
        }
        if (p.exp != null && p.exp * 1000 < System.currentTimeMillis()) {
            throw new BusinessException("X-Sivir(JWT) 已过期，请重新抓包录入");
        }
        Integer xcUserId = resolveUserVayne(p);
        Integer silkId = resolveSilkId(p);

        LoginStateEntity entity;
        if (id != null) {
            entity = loginStateMapper.selectById(id);
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                throw new BusinessException("无权修改该登录态");
            }
        } else {
            // 去重：同系统用户下同小蚕账号(xcUserId)只保留一条；重复录入则更新既有那条。
            if (xcUserId != null) {
                LoginStateEntity exist = loginStateMapper.selectOne(
                        new LambdaQueryWrapper<LoginStateEntity>()
                                .eq(LoginStateEntity::getUserId, user.getId())
                                .eq(LoginStateEntity::getUserVayne, xcUserId)
                                .last("limit 1"));
                if (exist != null) {
                    entity = exist;
                    id = exist.getId();
                } else {
                    entity = new LoginStateEntity();
                    entity.setUserId(user.getId());
                }
            } else {
                entity = new LoginStateEntity();
                entity.setUserId(user.getId());
            }
        }
        // 绑定地址校验：locationId 非空时必须属于当前用户
        Long locationId = dto.getLocationId();
        if (locationId != null) {
            LocationEntity loc = locationService.getById(locationId);
            if (loc == null || !loc.getUserId().equals(user.getId())) {
                throw new BusinessException("无权绑定该地址");
            }
            entity.setLocationId(locationId);
        }
        entity.setName(StringUtils.hasText(dto.getName()) ? dto.getName()
                : "账号" + (xcUserId == null ? "" : xcUserId));
        entity.setSivir(p.sivir);
        entity.setSessionId(p.sessionId);
        entity.setUserVayne(xcUserId);
        entity.setNami(StringUtils.hasText(p.nami) ? p.nami : null);
        entity.setSilkId(silkId == null ? 0 : silkId);
        entity.setCityCode(p.cityCode);
        // 保留原始抓包 header 留底
        entity.setRawHeaders(dto.getRawHeaders());
        if (p.exp != null) {
            entity.setExpireAt(new Date(p.exp * 1000).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (entity.getId() == null) {
            loginStateMapper.insert(entity);
        } else {
            loginStateMapper.updateById(entity);
        }
        log.info("登录态已保存 id={}, userVayne={}, silkId={}, cityCode={}, locationId={}, expireAt={}",
                entity.getId(), xcUserId, silkId, p.cityCode, entity.getLocationId(), entity.getExpireAt());
        return entity;
    }

    @Override
    public List<LoginStateVO> list() {
        Integer uid = userService.getByCurrentRequest().getId();
        List<LoginStateEntity> list = loginStateMapper.selectList(
                new LambdaQueryWrapper<LoginStateEntity>()
                        .eq(LoginStateEntity::getUserId, uid)
                        .orderByDesc(LoginStateEntity::getId));
        // 内存组装地址名（不联表）
        Map<Long, String> locNameMap = new HashMap<>();
        for (LocationEntity loc : locationService.list(
                new LambdaQueryWrapper<LocationEntity>().eq(LocationEntity::getUserId, uid))) {
            locNameMap.put(loc.getId(), loc.getName());
        }
        LocalDateTime now = LocalDateTime.now();
        return list.stream().map(e -> {
            LoginStateVO vo = new LoginStateVO();
            vo.setId(e.getId());
            vo.setName(e.getName());
            vo.setUserVayne(e.getUserVayne());
            vo.setSilkId(e.getSilkId());
            vo.setCityCode(e.getCityCode());
            vo.setExpireAt(e.getExpireAt());
            vo.setUpdateTime(e.getUpdateTime());
            vo.setLocationId(e.getLocationId());
            if (e.getLocationId() != null) {
                vo.setLocationName(locNameMap.get(e.getLocationId()));
            }
            if (e.getExpireAt() == null) {
                vo.setExpireStatus("未知");
            } else if (e.getExpireAt().isBefore(now)) {
                vo.setExpireStatus("已过期");
            } else if (e.getExpireAt().isBefore(now.plusDays(1))) {
                vo.setExpireStatus("即将过期");
            } else {
                vo.setExpireStatus("有效");
            }
            return vo;
        }).toList();
    }

    @Override
    public void delete(Integer id) {
        Integer uid = userService.getByCurrentRequest().getId();
        LoginStateEntity entity = loginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(uid)) {
            throw new BusinessException("无权操作该登录态");
        }
        loginStateMapper.deleteById(id);
    }

    @Override
    public LoginStateEntity getEntity(Integer id) {
        if (id == null) return null;
        UserEntity user = userService.getByCurrentRequest();
        LoginStateEntity entity = loginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(user.getId())) return null;
        return entity;
    }

    @Override
    public LoginStateEntity getEntityByIdAndUser(Integer id, Integer userId) {
        if (id == null || userId == null) return null;
        LoginStateEntity entity = loginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) return null;
        return entity;
    }

    @Override
    public LoginStateEntity getByIdAndOwner(Integer id) {
        UserEntity user = userService.getByCurrentRequest();
        LoginStateEntity entity = loginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作该登录态");
        }
        return entity;
    }

    @Override
    public void bindLocation(Integer id, Long locationId) {
        UserEntity user = userService.getByCurrentRequest();
        LoginStateEntity entity = loginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作该登录态");
        }
        Long target = null;
        if (locationId != null) {
            LocationEntity loc = locationService.getById(locationId);
            if (loc == null || !loc.getUserId().equals(user.getId())) {
                throw new BusinessException("无权绑定该地址");
            }
            target = locationId;
        }
        // 只更新 location_id 一列，不触碰 sivir/sessionId/rawHeaders 等明文字段。
        // 用 UpdateWrapper.set 显式写入（含 null），否则 updateById 会忽略 null 字段，
        // 解绑时无 SET 列导致 SQL 语法错。
        loginStateMapper.update(null,
                new LambdaUpdateWrapper<LoginStateEntity>()
                        .eq(LoginStateEntity::getId, id)
                        .set(LoginStateEntity::getLocationId, target));
        log.info("登录态绑定地址 id={}, locationId={}", id, target);
    }

    @Override
    public GrabAuth toGrabAuth(Integer id) {
        LoginStateEntity e = getByIdAndOwner(id);
        return GrabAuth.builder()
                .sivir(e.getSivir())
                .userId(e.getUserVayne())
                .sessionId(e.getSessionId())
                .nami(e.getNami())
                .silkId(e.getSilkId())
                .build();
    }

    @Override
    public LotteryAuth toLotteryAuth(Integer id) {
        LoginStateEntity e = getByIdAndOwner(id);
        return LotteryAuth.builder()
                .silkId(e.getSilkId())
                .userVayne(e.getUserVayne())
                .sessionId(e.getSessionId())
                .sivir(e.getSivir())
                .cityCode(e.getCityCode())
                .build();
    }
}
