package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.LotteryAuth;
import io.github.xiaocan.http.LotteryHttp;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;
import io.github.xiaocan.service.LotteryService;
import io.github.xiaocan.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 霸王餐刷浏览任务服务实现（App / Android 登录态）。
 *
 * 流程：解析 App 登录态 → LotteryInfo 查未完成浏览任务 → 逐个 AddLotteryTimes(type) → GetLotteryProgress 对比机会数。
 * type→任务映射详见 .trellis/tasks/07-15-bawangcan-lottery-app-auth/research/capture-app-lottery.md。
 */
@Slf4j
@Service
public class LotteryServiceImpl implements LotteryService {

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
    private io.github.xiaocan.service.LoginStateService loginStateService;
    @Resource
    private UserService userService;

    @Override
    public LotteryTaskResultVO runTask(Integer authId) {
        UserEntity user = userService.getByCurrentRequest();
        io.github.xiaocan.model.entity.LoginStateEntity entity = loginStateService.getEntity(authId);
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
            // 1) 刷前快照：失败只丢统计数字，不丢明细（独立 try）
            try {
                JSONObject beforeProgress = lotteryHttp.getLotteryProgress(auth);
                vo.setBeforeCount(getLotteryCount(beforeProgress));
            } catch (Exception e) {
                log.warn("刷前 getLotteryProgress 失败（不影响明细）: {}", e.getMessage());
            }

            // 2) lotteryInfo：查未完成项（核心，放遍历前，保证后续明细必填）
            JSONObject info;
            JSONObject li;
            try {
                info = lotteryHttp.lotteryInfo(auth);
                li = info == null ? null : info.getJSONObject("lottery_info");
                vo.setBeforeDayNum(li == null ? null : li.getInteger("day_num"));
            } catch (Exception e) {
                // lotteryInfo 都失败 → 代理完全不可用，记录 error 并直接结束（无明细可填）
                log.error("刷霸王餐 lotteryInfo 失败 authId={}", authId, e);
                vo.setError(friendlyMsg(e.getMessage(), -1));
                return vo;
            }

            // 3) 遍历全部浏览/领取类任务：已完成记 SKIPPED，未完成调 AddLotteryTimes 记 OK/FAIL
            for (Map.Entry<String, Integer> entry : FLAG_TO_TYPE.entrySet()) {
                String flag = entry.getKey();
                int type = entry.getValue();
                LotteryTaskResultVO.TaskItem item = new LotteryTaskResultVO.TaskItem();
                item.setType(type);
                item.setDesc(TYPE_TO_DESC.getOrDefault(type, "type=" + type));

                Boolean done = li == null ? null : li.getBoolean(flag);
                if (Boolean.TRUE.equals(done)) {
                    // 已完成，跳过未调用
                    item.setStatus(LotteryTaskResultVO.TaskStatus.SKIPPED);
                    item.setOk(false);
                    item.setMsg("已完成");
                    items.add(item);
                    continue;
                }
                // 未完成（含 li==null 或 flag=false）：尝试调用
                try {
                    JSONObject r = lotteryHttp.addLotteryTimes(auth, type);
                    JSONObject status = r == null ? null : r.getJSONObject("status");
                    int code = status == null ? -1 : status.getIntValue("code");
                    boolean ok = code == 0;
                    item.setStatus(ok ? LotteryTaskResultVO.TaskStatus.OK : LotteryTaskResultVO.TaskStatus.FAIL);
                    item.setOk(ok);
                    if (!ok) {
                        item.setMsg(friendlyMsg(status == null ? null : status.getString("msg"), code));
                    }
                } catch (Exception e) {
                    item.setStatus(LotteryTaskResultVO.TaskStatus.FAIL);
                    item.setOk(false);
                    item.setMsg(friendlyMsg(e.getMessage(), -1));
                    log.warn("addLotteryTimes type={} 异常: {}", type, e.getMessage());
                }
                items.add(item);
            }

            // 4) 刷后快照（统计数字，失败不丢明细，独立 try）
            try {
                JSONObject afterProgress = lotteryHttp.getLotteryProgress(auth);
                vo.setAfterCount(getLotteryCount(afterProgress));
                JSONObject afterInfo = lotteryHttp.lotteryInfo(auth);
                JSONObject afterLi = afterInfo == null ? null : afterInfo.getJSONObject("lottery_info");
                vo.setAfterDayNum(afterLi == null ? null : afterLi.getInteger("day_num"));
            } catch (Exception e) {
                log.warn("刷后快照失败（不影响明细）: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("刷霸王餐浏览任务失败 authId={}", authId, e);
            vo.setError(friendlyMsg(e.getMessage(), -1));
        }
        return vo;
    }

    private Integer getLotteryCount(JSONObject progress) {
        if (progress == null) return null;
        JSONObject lp = progress.getJSONObject("lottery_progress");
        return lp == null ? null : lp.getInteger("lottery_count");
    }

    /**
     * 把单任务失败原因转成友好文案（与前端顶层 friendlyError 映射对齐）。
     *
     * @param raw  原始原因（AddLotteryTimes status.msg 或异常 message）
     * @param code AddLotteryTimes status.code；异常传入 -1
     */
    private String friendlyMsg(String raw, int code) {
        if (code == 401) {
            return "当日加机会次数已满或权限不足";
        }
        if (raw == null) {
            return "无响应";
        }
        if (raw.contains("状态码错误:-1") || raw.contains("状态码错误:403")) {
            return "代理不可用（403 或超时），请更换代理后重试";
        }
        if (raw.contains("代理不可用")) {
            return "代理池为空，请配置代理后重试";
        }
        if (raw.contains("登录态不完整")) {
            return "登录态不完整，请补全 silk_id/X-Session-Id/X-Sivir";
        }
        return raw;
    }
}
