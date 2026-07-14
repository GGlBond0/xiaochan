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
}
