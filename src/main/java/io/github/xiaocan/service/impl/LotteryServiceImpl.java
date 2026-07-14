package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.LotteryAuth;
import io.github.xiaocan.http.LotteryHttp;
import io.github.xiaocan.mapper.LotteryAuthMapper;
import io.github.xiaocan.model.dto.LotteryAuthDTO;
import io.github.xiaocan.model.entity.LotteryAuthEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.vo.LotteryAuthVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;
import io.github.xiaocan.service.LotteryService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 霸王餐刷浏览任务服务实现（App / Android 登录态）。
 *
 * 流程：解析 App 登录态 → LotteryInfo 查未完成浏览任务 → 逐个 AddLotteryTimes(type) → GetLotteryProgress 对比机会数。
 * type→任务映射详见 .trellis/tasks/07-15-bawangcan-lottery-app-auth/research/capture-app-lottery.md。
 */
@Slf4j
@Service
public class LotteryServiceImpl implements LotteryService {

    private static final Pattern HEADER_LINE =
            Pattern.compile("(?i)^\\s*\"?([A-Za-z-]+)\"?\\s*[:：]\\s*\"?(.*?)\"?\\s*$");

    /**
     * LotteryInfo.lottery_info 标志位 → AddLotteryTimes.type 映射（已抓包确认）。
     * 仅含"浏览/领取类"任务；下单/签到等非浏览类不在范围。
     */
    private static final Map<String, Integer> FLAG_TO_TYPE = new LinkedHashMap<>();
    /**
     * type → 任务描述
     */
    private static final Map<Integer, String> TYPE_TO_DESC = new LinkedHashMap<>();

    static {
        // flag → type
        FLAG_TO_TYPE.put("if_shared", 2);
        FLAG_TO_TYPE.put("is_get_meituan_redpack", 8);
        FLAG_TO_TYPE.put("is_get_eleme_redpack", 9);
        FLAG_TO_TYPE.put("is_view_welfare_page", 10);
        FLAG_TO_TYPE.put("is_view_bwc_page", 11);
        // type → desc
        TYPE_TO_DESC.put(2, "分享");
        TYPE_TO_DESC.put(8, "领美团红包");
        TYPE_TO_DESC.put(9, "领饿了么红包");
        TYPE_TO_DESC.put(10, "浏览福利页");
        TYPE_TO_DESC.put(11, "浏览霸王餐页");
        // 注：is_view_tp_ad / is_view_douyin_mall 在 App 端不走 AddLotteryTimes（WebView 计时自动标记），纯接口刷不到，故不在此映射。
    }

    private final LotteryHttp lotteryHttp = new LotteryHttp();

    @Resource
    private LotteryAuthMapper lotteryAuthMapper;
    @Resource
    private UserService userService;

    @Override
    public Integer saveAuth(LotteryAuthDTO dto, Integer id) {
        UserEntity user = userService.getByCurrentRequest();
        String raw = dto.getRawHeaders();
        // 兼容抓包 JSON：提取 headers 节点
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
        String sessionId = null, sivir = null, vayne = null, teemo = null;
        Integer cityCode = null;
        // 抓包 JSON body 取 silk_id 兜底
        Integer bodySilkId = parseSilkIdFromBody(dto.getRawHeaders());
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = HEADER_LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase();
            String val = m.group(2).trim();
            if (val.startsWith("[")) {
                val = val.replaceAll("[\\[\\]\"\\\\]", "");
            }
            switch (key) {
                case "x-session-id" -> sessionId = val;
                case "x-sivir" -> sivir = val;
                case "x-vayne" -> vayne = val;
                case "x-teemo" -> teemo = val;
                case "x-city", "x-citycode" -> {
                    if (cityCode == null && StringUtils.hasText(val)) {
                        try { cityCode = Integer.parseInt(val); } catch (Exception ignore) { }
                    }
                }
                default -> { }
            }
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException("未解析到登录态：缺少 X-Session-Id");
        }
        if (!StringUtils.hasText(sivir)) {
            throw new BusinessException("未解析到登录态：缺少 X-Sivir（App 登录态 JWT）");
        }
        Integer silkId = bodySilkId;
        if (silkId == null && StringUtils.hasText(teemo)) {
            try { silkId = Integer.parseInt(teemo); } catch (Exception ignore) { }
        }
        if (silkId == null) {
            throw new BusinessException("未解析到 silk_id：缺少 x-Teemo 或 body.silk_id");
        }
        Integer userVayne = null;
        if (StringUtils.hasText(vayne)) {
            try { userVayne = Integer.parseInt(vayne); } catch (Exception ignore) { }
        }

        LotteryAuthEntity entity;
        if (id != null) {
            entity = lotteryAuthMapper.selectById(id);
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                throw new BusinessException("无权修改该登录态");
            }
        } else {
            // 去重：同系统用户下同 silk_id 只保留一条
            LotteryAuthEntity exist = lotteryAuthMapper.selectOne(
                    new LambdaQueryWrapper<LotteryAuthEntity>()
                            .eq(LotteryAuthEntity::getUserId, user.getId())
                            .eq(LotteryAuthEntity::getSilkId, silkId)
                            .last("limit 1"));
            entity = exist != null ? exist : new LotteryAuthEntity();
            entity.setUserId(user.getId());
        }
        entity.setName(StringUtils.hasText(dto.getName()) ? dto.getName() : "账号" + silkId);
        entity.setSilkId(silkId);
        entity.setUserVayne(userVayne);
        entity.setSessionId(sessionId);
        entity.setSivir(sivir);
        entity.setCityCode(cityCode);
        entity.setRawHeaders(dto.getRawHeaders());
        if (entity.getId() == null) {
            lotteryAuthMapper.insert(entity);
        } else {
            lotteryAuthMapper.updateById(entity);
        }
        return entity.getId();
    }

    @Override
    public List<LotteryAuthVO> listAuth() {
        UserEntity user = userService.getByCurrentRequest();
        List<LotteryAuthEntity> list = lotteryAuthMapper.selectList(
                new LambdaQueryWrapper<LotteryAuthEntity>()
                        .eq(LotteryAuthEntity::getUserId, user.getId())
                        .orderByDesc(LotteryAuthEntity::getUpdateTime));
        List<LotteryAuthVO> result = new ArrayList<>();
        for (LotteryAuthEntity e : list) {
            LotteryAuthVO vo = new LotteryAuthVO();
            vo.setId(e.getId());
            vo.setName(e.getName());
            vo.setSilkId(e.getSilkId());
            vo.setUserVayne(e.getUserVayne());
            vo.setSessionId(e.getSessionId());
            vo.setCityCode(e.getCityCode());
            vo.setUpdateTime(e.getUpdateTime());
            result.add(vo);
        }
        return result;
    }

    @Override
    public void deleteAuth(Integer id) {
        UserEntity user = userService.getByCurrentRequest();
        LotteryAuthEntity entity = lotteryAuthMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作该登录态");
        }
        lotteryAuthMapper.deleteById(id);
    }

    @Override
    public LotteryTaskResultVO runTask(Integer authId) {
        UserEntity user = userService.getByCurrentRequest();
        LotteryAuthEntity entity = lotteryAuthMapper.selectById(authId);
        if (entity == null || !entity.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作该登录态");
        }
        LotteryAuth auth = LotteryAuth.builder()
                .silkId(entity.getSilkId())
                .userVayne(entity.getUserVayne())
                .sessionId(entity.getSessionId())
                .sivir(entity.getSivir())
                .cityCode(entity.getCityCode())
                .build();
        if (!auth.isComplete()) {
            throw new BusinessException("登录态不完整：silk_id 或 X-Session-Id 或 X-Sivir 缺失");
        }

        LotteryTaskResultVO vo = new LotteryTaskResultVO();
        vo.setAuthName(entity.getName());
        List<LotteryTaskResultVO.TaskItem> items = new ArrayList<>();
        vo.setTasks(items);

        try {
            // 刷前快照
            JSONObject beforeProgress = lotteryHttp.getLotteryProgress(auth);
            vo.setBeforeCount(getLotteryCount(beforeProgress));
            JSONObject info = lotteryHttp.lotteryInfo(auth);
            JSONObject li = info.getJSONObject("lottery_info");
            vo.setBeforeDayNum(li == null ? null : li.getInteger("day_num"));

            // 遍历未完成的浏览/领取类任务，按映射调 AddLotteryTimes
            if (li != null) {
                for (Map.Entry<String, Integer> entry : FLAG_TO_TYPE.entrySet()) {
                    String flag = entry.getKey();
                    int type = entry.getValue();
                    Boolean done = li.getBoolean(flag);
                    if (done != null && done) {
                        continue; // 已完成，跳过
                    }
                    LotteryTaskResultVO.TaskItem item = new LotteryTaskResultVO.TaskItem();
                    item.setType(type);
                    item.setDesc(TYPE_TO_DESC.getOrDefault(type, "type=" + type));
                    try {
                        JSONObject r = lotteryHttp.addLotteryTimes(auth, type);
                        JSONObject status = r.getJSONObject("status");
                        int code = status == null ? -1 : status.getIntValue("code");
                        item.setOk(code == 0);
                        if (code != 0) {
                            item.setMsg(status == null ? "无status响应" : status.getString("msg"));
                        }
                    } catch (Exception e) {
                        item.setOk(false);
                        item.setMsg(e.getMessage());
                        log.warn("addLotteryTimes type={} 异常: {}", type, e.getMessage());
                    }
                    items.add(item);
                }
            }

            // 刷后快照
            JSONObject afterProgress = lotteryHttp.getLotteryProgress(auth);
            vo.setAfterCount(getLotteryCount(afterProgress));
            JSONObject afterInfo = lotteryHttp.lotteryInfo(auth);
            JSONObject afterLi = afterInfo == null ? null : afterInfo.getJSONObject("lottery_info");
            vo.setAfterDayNum(afterLi == null ? null : afterLi.getInteger("day_num"));
        } catch (Exception e) {
            log.error("刷霸王餐浏览任务失败 authId={}", authId, e);
            vo.setError(e.getMessage());
        }
        return vo;
    }

    private Integer getLotteryCount(JSONObject progress) {
        if (progress == null) return null;
        JSONObject lp = progress.getJSONObject("lottery_progress");
        return lp == null ? null : lp.getInteger("lottery_count");
    }

    private Integer parseSilkIdFromBody(String raw) {
        if (raw == null || !raw.trim().startsWith("{")) return null;
        try {
            JSONObject json = JSONObject.parseObject(raw);
            JSONObject body = json.getJSONObject("body");
            if (body != null) return body.getInteger("silk_id");
        } catch (Exception ignore) { }
        return null;
    }
}
