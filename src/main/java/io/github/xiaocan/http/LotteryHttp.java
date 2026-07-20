package io.github.xiaocan.http;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
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
    /**
     * 执行抽奖（开红包，2026-07-21 抓包确认，见
     * .trellis/tasks/07-21-lottery-draw-redpack/prd.md）。
     * body 带 prize_type=1，每调一次 lottery_count 减 1，返回 prize（打车券/券包/小蚕红包等）。
     */
    private static final String LOTTERY_METHOD = "SilkwormLotteryMobile.Lottery";
    /**
     * 看视频/看商城完成上报（OnAdViewed，2026-07-20 抓包+H5逆向确认，见
     * .trellis/tasks/07-20-lottery-extra-tasks/research/capture-extra-tasks.md）。
     * bus_type=2 看视频(is_view_tp_ad)，bus_type=4 看商城(is_view_douyin_mall)。
     */
    private static final String ON_AD_VIEWED_METHOD = "SilkwormLotteryMobile.OnAdViewed";
    /** 领取累计抽奖阶梯奖（step=1 first，step=2 second） */
    private static final String RECEIVE_EXTRA_LOTTERY_METHOD = "SilkwormLotteryMobile.ReceiveExtraLottery";

    /** OnAdViewed sign 的 HMAC-SHA256 密钥（H5 源码硬编码，全账号通用） */
    private static final String ON_AD_VIEWED_SIGN_KEY = "lcjkbqadfrzsewxy";

    /** OnAdViewed bus_type：看视频（is_view_tp_ad） */
    public static final int BUS_TYPE_VIEW_TP_AD = 2;
    /** OnAdViewed bus_type：看商城（is_view_douyin_mall） */
    public static final int BUS_TYPE_VIEW_DOUYIN_MALL = 4;

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
     * 执行抽奖（开红包），消费 1 次抽奖机会。
     * <p>
     * 抓包确认（2026-07-21）：body `{"silk_id":N,"prize_type":1}`，每调一次 lottery_count 减 1，
     * 响应 `{"status":{"code":0},"prize":{...},"lucky_times":0,"is_lucky":false,"verify_method":0}`。
     * prize_type 固定 1（抓包仅此值，未验证其它）。无 sign。
     *
     * @param auth 登录态
     */
    public JSONObject lottery(LotteryAuth auth) {
        Map<String, Object> body = baseBody(auth);
        body.put("prize_type", 1);
        return postAuth(JSONObject.toJSONString(body), LOTTERY_SERVER, LOTTERY_METHOD, auth, "lottery");
    }

    /**
     * 看视频/看商城完成上报 +1 抽奖机会（OnAdViewed，带 HMAC-SHA256 sign）。
     * <p>
     * 抓包确认（2026-07-20）：bus_type=2 看视频翻转 is_view_tp_ad，bus_type=4 看商城翻转 is_view_douyin_mall，
     * 每次成功 day_num+1。sign 算法：HMAC-SHA256(密钥 "lcjkbqadfrzsewxy",
     * "silk_id={s}&timestamp={秒}&nonce={6位随机小写}&bus_type={b}")，base64 输出。已实测两样本 MATCH。
     *
     * @param auth    登录态
     * @param busType {@link #BUS_TYPE_VIEW_TP_AD}=看视频, {@link #BUS_TYPE_VIEW_DOUYIN_MALL}=看商城
     */
    public JSONObject onAdViewed(LotteryAuth auth, int busType) {
        long tsSec = System.currentTimeMillis() / 1000;
        String nonce = randomNonce6();
        String signStr = "silk_id=" + auth.getSilkId()
                + "&timestamp=" + tsSec
                + "&nonce=" + nonce
                + "&bus_type=" + busType;
        byte[] signBytes = new HMac(HmacAlgorithm.HmacSHA256, ON_AD_VIEWED_SIGN_KEY.getBytes()).digest(signStr);
        String sign = cn.hutool.core.codec.Base64.encode(signBytes);
        Map<String, Object> body = baseBody(auth);
        body.put("timestamp", tsSec);
        body.put("nonce", nonce);
        body.put("bus_type", busType);
        body.put("sign", sign);
        return postAuth(JSONObject.toJSONString(body), LOTTERY_SERVER, ON_AD_VIEWED_METHOD, auth, "onAdViewed");
    }

    /**
     * 领取累计抽奖阶梯奖（ReceiveExtraLottery）。
     * <p>
     * 抓包确认（2026-07-20 H5 逆向）：step=1 领 first 阶梯奖，step=2 领 second 阶梯奖。
     * 触发条件 lottery_count &gt;= {first|second}_step_count 且 !has_got_{...}_step_prize；
     * 已领取返回业务码 40043。
     *
     * @param auth 登录态
     * @param step 1=first, 2=second
     */
    public JSONObject receiveExtraLottery(LotteryAuth auth, int step) {
        Map<String, Object> body = baseBody(auth);
        body.put("step", step);
        return postAuth(JSONObject.toJSONString(body), LOTTERY_SERVER, RECEIVE_EXTRA_LOTTERY_METHOD, auth, "receiveExtraLottery");
    }

    /**
     * 生成 6 位随机小写字母 nonce（OnAdViewed sign 用，与 H5 源码同算法）。
     */
    private static String randomNonce6() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
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
