package io.github.xiaocan.http;

import cn.hutool.crypto.digest.MD5;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.config.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * 小蚕霸王餐抽奖 RPC 调用（App / Android 登录态）。
 * 复用 XiaochanHttp 的 X-Ashe 签名算法 + ProxyHolder 代理重试机制，走 Android header（X-Platform=Android + X-Sivir JWT）。
 *
 * 抓包确认（详见 .trellis/tasks/07-15-bawangcan-lottery-app-auth/research/capture-app-lottery.md）：
 * 端点 gwh.xiaocantech.com，body 无 app_id，仅 silk_id（加机会多 type）。
 * - SilkwormLotteryMobile.LotteryInfo       查抽奖机会来源（is_view_xxx 未完成项）
 * - SilkwormLotteryMobile.AddLotteryTimes   完成浏览任务 +1 机会（无验证，纯接口可刷）
 * - SilkwormLotteryMobile.GetLotteryProgress 查当前机会数 lottery_count（含阶梯 first/second_step_count）
 */
@Slf4j
public class LotteryHttp {

    private static final String BASE_URL = "https://gwh.xiaocantech.com/rpc";

    /** 霸王餐抽奖服务名/方法名 */
    private static final String LOTTERY_SERVER = "SilkwormLottery";
    private static final String LOTTERY_INFO_METHOD = "SilkwormLotteryMobile.LotteryInfo";
    private static final String ADD_LOTTERY_METHOD = "SilkwormLotteryMobile.AddLotteryTimes";
    private static final String LOTTERY_PROGRESS_METHOD = "SilkwormLotteryMobile.GetLotteryProgress";

    /** 任务/积分状态服务 */
    private static final String TASK_SERVER = "ActivityTask";
    private static final String USER_TASK_METHOD = "ActivityTaskMobileService.UserTaskV2";

    /**
     * 获取 X-Ashe 签名（与 XiaochanHttp.getAshe 同算法）。
     * x = md5((server + "." + method).toLowerCase())
     * ashe = md5(x + timeMillis + nami)
     */
    private static String getAshe(Long timeMillis, String serverName, String methodName, String nami) {
        String x = MD5.create().digestHex((serverName + "." + methodName).toLowerCase());
        return MD5.create().digestHex(x + timeMillis + nami);
    }

    /**
     * 生成 X-Nami：UUID 去横线后插入 silk_id，凑成 16 位 hex（与 XiaochanHttp.getNami 同算法）。
     */
    private static String getNami(String silkId) {
        String uuid = generateUuid().replace("-", "");
        String id = silkId == null ? "0" : silkId;
        return uuid.substring(0, 4) + id + uuid.substring(4, 20 - id.length() - 4);
    }

    /**
     * 查抽奖机会来源（哪些浏览任务未完成）。
     */
    public JSONObject lotteryInfo(LotteryAuth auth) {
        return postAuth(getBody(auth), LOTTERY_SERVER, LOTTERY_INFO_METHOD, auth, "lotteryInfo");
    }

    /**
     * 完成浏览任务 +1 抽奖机会（无验证，纯接口可刷）。
     *
     * @param auth 登录态
     * @param type 浏览任务类型（2=分享, 8=领美团红包, 9=领饿了么红包, 10=浏览福利页, 11=浏览霸王餐页，详见 research/type-map.md）
     */
    public JSONObject addLotteryTimes(LotteryAuth auth, int type) {
        Map<String, Object> body = baseBody(auth);
        body.put("type", type);
        return postAuth(JSONObject.toJSONString(body), LOTTERY_SERVER, ADD_LOTTERY_METHOD, auth, "addLotteryTimes");
    }

    /**
     * 查当前抽奖机会数（lottery_count）。
     */
    public JSONObject getLotteryProgress(LotteryAuth auth) {
        return postAuth(getBody(auth), LOTTERY_SERVER, LOTTERY_PROGRESS_METHOD, auth, "getLotteryProgress");
    }

    /**
     * 查任务/积分状态（可选，辅助展示）。
     */
    public JSONObject userTaskV2(LotteryAuth auth) {
        return postAuth(getBody(auth), TASK_SERVER, USER_TASK_METHOD, auth, "userTaskV2");
    }

    private String getBody(LotteryAuth auth) {
        return JSONObject.toJSONString(baseBody(auth));
    }

    private Map<String, Object> baseBody(LotteryAuth auth) {
        Map<String, Object> body = new HashMap<>();
        body.put("silk_id", auth.getSilkId());
        return body;
    }

    private JSONObject postAuth(String body, String serverName, String methodName, LotteryAuth auth, String tag) {
        Long timeMillis = System.currentTimeMillis();
        String nami = getNami(String.valueOf(auth.getSilkId()));
        String ashe = getAshe(timeMillis, serverName, methodName, nami);
        HttpResponse response = executeWithProxy(proxy -> HttpUtil.createPost(BASE_URL)
                .headerMap(getAndroidHeaders(timeMillis, ashe, serverName, methodName, nami, auth), true)
                .timeout(ProxyHolder.requestTimeout())
                .body(body), tag);
        if (response == null || !response.isOk()) {
            int status = response == null ? -1 : response.getStatus();
            String rb = response == null ? "" : response.body();
            if (response != null) response.close();
            if (status == 401) {
                // 业务拒绝（当日加机会次数已满 is_add_times=false 等），返回结构化失败而非抛异常，供上层记录
                log.warn("{} 401 业务拒绝: {}", tag, rb);
                JSONObject fail = new JSONObject();
                JSONObject st = new JSONObject();
                st.put("code", 401);
                st.put("msg", "当日加机会次数已满或权限不足");
                fail.put("status", st);
                return fail;
            }
            log.error("{} 状态码错误: {}, body: {}", tag, status, rb);
            throw new BusinessException("状态码错误:" + status);
        }
        String resBody = response.body();
        response.close();
        return JSONObject.parseObject(resBody);
    }

    /**
     * 经代理执行上游 HTTP 请求；代理未启用则直连。
     * 403/网络异常 → 失效代理并换代理重试（与 XiaochanHttp.executeWithProxy 同逻辑）；
     * 401 → 业务拒绝（当日次数已满等），直接返回不重试。
     */
    private HttpResponse executeWithProxy(Function<String[], HttpRequest> reqFn, String tag) {
        if (!ProxyHolder.enabled()) {
            return reqFn.apply(null).execute();
        }
        int retry = ProxyHolder.retry();
        for (int i = 0; i < retry; i++) {
            String[] proxy = ProxyHolder.getProxy(i > 0);
            if (proxy == null) {
                throw new BusinessException("代理不可用，无法请求小蚕网关");
            }
            HttpRequest req = reqFn.apply(proxy);
            req.setHttpProxy(proxy[0], Integer.parseInt(proxy[1]));
            HttpResponse response;
            try {
                response = req.execute();
            } catch (Exception e) {
                log.warn("{} 经代理 {}:{} 请求异常，换代理重试({}/{}): {}", tag, proxy[0], proxy[1], i + 1, retry, e.getMessage());
                ProxyHolder.invalidate();
                continue;
            }
            if (response.getStatus() == 403) {
                // 403 = 代理被风控/封禁，换代理重试
                log.warn("{} 经代理 {}:{} 返回 403，换代理重试({}/{})", tag, proxy[0], proxy[1], i + 1, retry);
                response.close();
                ProxyHolder.invalidate();
                continue;
            }
            if (response.getStatus() == 401) {
                // 401 = 业务拒绝（如当日加机会次数已满 is_add_times=false），非代理问题，直接返回不重试
                log.warn("{} 返回 401（业务拒绝，如当日次数已满），不重试", tag);
                return response;
            }
            return response;
        }
        return null;
    }

    /**
     * App(Android) 登录态请求头（小蚕 App 抓包值，端点 gwh.xiaocantech.com）。
 * body 无 app_id，header 无 appid；X-Sivir JWT 必填。
     */
    private Map<String, String> getAndroidHeaders(Long timeMillis, String ashe, String serverName,
                                                   String methodName, String nami, LotteryAuth auth) {
        Map<String, String> headers = new HashMap<>();
        headers.put("servername", serverName);
        headers.put("methodname", methodName);
        headers.put("X-Ashe", ashe);
        headers.put("X-Nami", nami);
        headers.put("X-Garen", String.valueOf(timeMillis));
        headers.put("X-Platform", "Android");
        headers.put("X-Version", "3.18.3.3");
        headers.put("x-Annie", "XC");
        headers.put("X-Session-Id", auth.getSessionId());
        headers.put("x-Teemo", String.valueOf(auth.getSilkId()));
        if (auth.getUserVayne() != null) {
            headers.put("X-Vayne", String.valueOf(auth.getUserVayne()));
        }
        headers.put("X-Sivir", auth.getSivir());
        if (auth.getCityCode() != null) {
            headers.put("x-City", String.valueOf(auth.getCityCode()));
        }
        headers.put("User-Agent", "XC;Android;3.18.3;");
        headers.put("Content-Type", "application/json; charset=utf-8");
        return headers;
    }

    /**
     * 生成 UUID（与 XiaochanHttp.generateUuid 同算法，模仿原始 JS 行为）。
     */
    public static String generateUuid() {
        char[] chars = new char[36];
        String hexChars = "0123456789abcdef";
        Random random = new Random();
        for (int i = 0; i < 36; i++) {
            chars[i] = hexChars.charAt(random.nextInt(16));
        }
        chars[14] = '4';
        chars[19] = hexChars.charAt((chars[19] & 0x3) | 0x8);
        chars[8] = chars[13] = chars[18] = chars[23] = '-';
        return new String(chars);
    }
}
