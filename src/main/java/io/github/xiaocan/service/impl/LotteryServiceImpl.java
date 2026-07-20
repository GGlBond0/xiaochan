package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.LotteryAuth;
import io.github.xiaocan.http.LotteryHttp;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.vo.LotteryDrawResultVO;
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
        // 注：is_view_tp_ad / is_view_douyin_mall 走独立方法 OnAdViewed（bus_type=2/4，带 HMAC sign），
        // 领阶梯奖走 ReceiveExtraLottery（step=1/2）。2026-07-20 抓包+H5 逆向确认，见
        // .trellis/tasks/07-20-lottery-extra-tasks/research/capture-extra-tasks.md。不走 AddLotteryTimes。
    }

    /** TaskItem.type 语义扩展：领阶梯奖用 101/102，避免与 AddLotteryTimes.type(2/8/9/10/11) 和 bus_type(2/4) 冲突 */
    private static final int TYPE_CLAIM_FIRST_STEP = 101;
    private static final int TYPE_CLAIM_SECOND_STEP = 102;

    private final LotteryHttp lotteryHttp = new LotteryHttp();

    @Resource
    private io.github.xiaocan.service.LoginStateService loginStateService;
    @Resource
    private UserService userService;

    @Override
    public LotteryTaskResultVO runTask(Integer authId) {
        AuthBundle bundle = resolveAuth(authId);
        LoginStateEntity entity = bundle.entity;
        LotteryAuth auth = bundle.auth;

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

            // 3.5) 看视频/看商城（OnAdViewed，独立于 AddLotteryTimes，带 HMAC sign）
            addAdViewTask(items, li, auth, "is_view_tp_ad", LotteryHttp.BUS_TYPE_VIEW_TP_AD, "看视频");
            addAdViewTask(items, li, auth, "is_view_douyin_mall", LotteryHttp.BUS_TYPE_VIEW_DOUYIN_MALL, "看商城");

            // 3.6) 领累计抽奖阶梯奖（ReceiveExtraLottery）——需取刷后最新 lottery_count
            try {
                JSONObject progress = lotteryHttp.getLotteryProgress(auth);
                JSONObject lp = progress == null ? null : progress.getJSONObject("lottery_progress");
                if (lp != null) {
                    addStepPrizeTask(items, lp, auth, 1, "first", "领第一阶梯奖", TYPE_CLAIM_FIRST_STEP);
                    addStepPrizeTask(items, lp, auth, 2, "second", "领第二阶梯奖", TYPE_CLAIM_SECOND_STEP);
                }
            } catch (Exception e) {
                log.warn("领阶梯奖 getLotteryProgress 失败（不影响明细）: {}", e.getMessage());
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

    /** 防御性循环硬上限：防止上游 lottery_count 异常值导致死循环 */
    private static final int DRAW_HARD_CAP = 50;

    @Override
    public LotteryDrawResultVO draw(Integer authId) {
        AuthBundle bundle = resolveAuth(authId);
        LoginStateEntity entity = bundle.entity;
        LotteryAuth auth = bundle.auth;

        LotteryDrawResultVO vo = new LotteryDrawResultVO();
        vo.setAuthName(entity.getName());
        List<LotteryDrawResultVO.DrawItem> prizes = new ArrayList<>();
        vo.setPrizes(prizes);

        // 开前快照：失败 beforeCount=null，不影响抽奖循环（N 取 0）
        Integer before = null;
        try {
            before = getLotteryCount(lotteryHttp.getLotteryProgress(auth));
        } catch (Exception e) {
            log.warn("开红包前 getLotteryProgress 失败（不影响抽奖）: {}", e.getMessage());
        }
        vo.setBeforeCount(before);
        int n = before == null ? 0 : Math.min(before, DRAW_HARD_CAP);

        for (int i = 0; i < n; i++) {
            try {
                JSONObject r = lotteryHttp.lottery(auth);
                int code = codeOf(r);
                if (code == 0) {
                    JSONObject p = r == null ? null : r.getJSONObject("prize");
                    prizes.add(toDrawItem(p, true, null));
                } else {
                    String msg = friendlyMsg(msgOf(r), code);
                    prizes.add(toDrawItem(null, false, msg));
                    vo.setError("第" + (i + 1) + "次抽奖失败: " + msg);
                    break;
                }
            } catch (Exception e) {
                String msg = friendlyMsg(e.getMessage(), -1);
                prizes.add(toDrawItem(null, false, msg));
                vo.setError("第" + (i + 1) + "次抽奖异常: " + e.getMessage());
                log.warn("lottery 第{}次异常: {}", i + 1, e.getMessage());
                break;
            }
        }

        // 开后快照：失败 afterCount=null
        try {
            vo.setAfterCount(getLotteryCount(lotteryHttp.getLotteryProgress(auth)));
        } catch (Exception e) {
            log.warn("开红包后 getLotteryProgress 失败（不影响明细）: {}", e.getMessage());
        }
        return vo;
    }

    /**
     * 鉴权 + 构造 LotteryAuth（runTask / draw 共用）。
     *
     * @param authId 登录态 id
     * @return 归属校验通过的 entity + 完整性校验通过的 auth
     */
    private AuthBundle resolveAuth(Integer authId) {
        UserEntity user = userService.getByCurrentRequest();
        LoginStateEntity entity = loginStateService.getEntity(authId);
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
        return new AuthBundle(entity, auth);
    }

    /** resolveAuth 返回包，避免多返回值 */
    private record AuthBundle(LoginStateEntity entity, LotteryAuth auth) {
    }

    /**
     * 把 lottery 响应 prize 转成 DrawItem（prize 为 null 时字段留空，用于失败条目）。
     */
    private LotteryDrawResultVO.DrawItem toDrawItem(JSONObject prize, boolean ok, String msg) {
        LotteryDrawResultVO.DrawItem item = new LotteryDrawResultVO.DrawItem();
        item.setOk(ok);
        item.setMsg(msg);
        if (prize != null) {
            item.setName(prize.getString("name"));
            item.setIcon(prize.getString("icon"));
            item.setFirstType(prize.getInteger("first_type"));
            item.setSecondType(prize.getInteger("second_type"));
            item.setCardId(prize.getInteger("card_id"));
        }
        return item;
    }

    private Integer getLotteryCount(JSONObject progress) {
        if (progress == null) return null;
        JSONObject lp = progress.getJSONObject("lottery_progress");
        return lp == null ? null : lp.getInteger("lottery_count");
    }

    /**
     * 看视频/看商城任务（OnAdViewed）。已完成记 SKIPPED，未完成调 onAdViewed 记 OK/FAIL。
     *
     * @param li      LotteryInfo.lottery_info（取 flag 翻转状态）
     * @param flag    is_view_tp_ad / is_view_douyin_mall
     * @param busType OnAdViewed bus_type（2/4）
     * @param desc    任务描述
     */
    private void addAdViewTask(List<LotteryTaskResultVO.TaskItem> items, JSONObject li, LotteryAuth auth,
                               String flag, int busType, String desc) {
        LotteryTaskResultVO.TaskItem item = new LotteryTaskResultVO.TaskItem();
        item.setType(busType);
        item.setDesc(desc);
        Boolean done = li == null ? null : li.getBoolean(flag);
        if (Boolean.TRUE.equals(done)) {
            item.setStatus(LotteryTaskResultVO.TaskStatus.SKIPPED);
            item.setOk(false);
            item.setMsg("已完成");
            items.add(item);
            return;
        }
        try {
            JSONObject r = lotteryHttp.onAdViewed(auth, busType);
            int code = codeOf(r);
            boolean ok = code == 0;
            item.setStatus(ok ? LotteryTaskResultVO.TaskStatus.OK : LotteryTaskResultVO.TaskStatus.FAIL);
            item.setOk(ok);
            if (!ok) {
                item.setMsg(friendlyMsg(msgOf(r), code));
            }
        } catch (Exception e) {
            item.setStatus(LotteryTaskResultVO.TaskStatus.FAIL);
            item.setOk(false);
            item.setMsg(friendlyMsg(e.getMessage(), -1));
            log.warn("onAdViewed busType={} 异常: {}", busType, e.getMessage());
        }
        items.add(item);
    }

    /**
     * 领累计抽奖阶梯奖（ReceiveExtraLottery）。
     * 已领取记 SKIPPED("已领取")，未达阈值记 SKIPPED("未达阶梯阈值")，达阈值调 receiveExtraLottery 记 OK/FAIL。
     *
     * @param lp      GetLotteryProgress.lottery_progress
     * @param step    1=first, 2=second
     * @param prefix  "first" / "second"（拼 step_count / has_got_*_step_prize 字段名）
     * @param desc    任务描述
     * @param typeVal TaskItem.type（101/102，区分阶梯奖）
     */
    private void addStepPrizeTask(List<LotteryTaskResultVO.TaskItem> items, JSONObject lp, LotteryAuth auth,
                                  int step, String prefix, String desc, int typeVal) {
        LotteryTaskResultVO.TaskItem item = new LotteryTaskResultVO.TaskItem();
        item.setType(typeVal);
        item.setDesc(desc);
        int count = lp.getIntValue("lottery_count");
        int stepCount = lp.getIntValue(prefix + "_step_count");
        boolean got = lp.getBooleanValue("has_got_" + prefix + "_step_prize");
        if (got) {
            item.setStatus(LotteryTaskResultVO.TaskStatus.SKIPPED);
            item.setOk(false);
            item.setMsg("已领取");
            items.add(item);
            return;
        }
        if (count < stepCount) {
            item.setStatus(LotteryTaskResultVO.TaskStatus.SKIPPED);
            item.setOk(false);
            item.setMsg("未达阶梯阈值");
            items.add(item);
            return;
        }
        try {
            JSONObject r = lotteryHttp.receiveExtraLottery(auth, step);
            int code = codeOf(r);
            boolean ok = code == 0;
            item.setStatus(ok ? LotteryTaskResultVO.TaskStatus.OK : LotteryTaskResultVO.TaskStatus.FAIL);
            item.setOk(ok);
            if (!ok) {
                item.setMsg(friendlyMsg(msgOf(r), code));
            }
        } catch (Exception e) {
            item.setStatus(LotteryTaskResultVO.TaskStatus.FAIL);
            item.setOk(false);
            item.setMsg(friendlyMsg(e.getMessage(), -1));
            log.warn("receiveExtraLottery step={} 异常: {}", step, e.getMessage());
        }
        items.add(item);
    }

    /** 取响应 status.code，异常/null 返回 -1 */
    private int codeOf(JSONObject r) {
        JSONObject status = r == null ? null : r.getJSONObject("status");
        return status == null ? -1 : status.getIntValue("code");
    }

    /** 取响应 status.msg，异常/null 返回 null */
    private String msgOf(JSONObject r) {
        JSONObject status = r == null ? null : r.getJSONObject("status");
        return status == null ? null : status.getString("msg");
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
        if (code == 40043) {
            return "阶梯奖已领取";
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
