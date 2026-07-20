# 技术设计：霸王餐新增看视频/看商城/领累计奖励任务

## 1. 边界

本次只改霸王餐刷任务链路（`LotteryHttp` + `LotteryServiceImpl` + 前端 `SettingsView.vue` 明细区），不动抢单/登录态/代理/推送。

- **不改**：`LotteryAuth`、`getAndroidHeaders`、`postAuth`、`executeWithProxy`、`getAshe`/`getNami`、`ProxyHolder`、`LoginStateService`、`/api/lottery/run` 接口签名。
- **不改**：`LotteryTaskResultVO` 字段结构（复用 `tasks: List<TaskItem>`，`type` 字段语义扩展）。

## 2. 接口契约（逆向确认，见 research/capture-extra-tasks.md）

### 2.1 OnAdViewed（看视频/看商城）
- endpoint `gwh.xiaocantech.com/rpc`，`servername=SilkwormLottery`，`methodname=SilkwormLotteryMobile.OnAdViewed`
- body：`{silk_id, timestamp(秒), nonce(6位随机小写), bus_type, sign}`
- sign：`base64(HMAC_SHA256("lcjkbqadfrzsewxy", "silk_id={s}&timestamp={ts}&nonce={n}&bus_type={b}"))`
- bus_type：2=看视频(`is_view_tp_ad`)，4=看商城(`is_view_douyin_mall`)
- 响应：`{"status":{"code":0}}` 成功

### 2.2 ReceiveExtraLottery（领阶梯奖）
- endpoint gwh，`servername=SilkwormLottery`，`methodname=SilkwormLotteryMobile.ReceiveExtraLottery`
- body：`{silk_id, step}`（step: 1=first, 2=second）
- 响应：`{"status":{"code":0}, "prize":{...}}` 成功；`code:40043`=已领取
- 触发条件：`lottery_count >= {first|second}_step_count && !has_got_{first|second}_step_prize`

## 3. LotteryHttp 改动

### 3.1 新增常量
```java
private static final String ON_AD_VIEWED_METHOD = "SilkwormLotteryMobile.OnAdViewed";
private static final String RECEIVE_EXTRA_LOTTERY_METHOD = "SilkwormLotteryMobile.ReceiveExtraLottery";
private static final String ON_AD_VIEWED_SIGN_KEY = "lcjkbqadfrzsewxy";
// bus_type
public static final int BUS_TYPE_VIEW_TP_AD = 2;   // 看视频
public static final int BUS_TYPE_VIEW_DOUYIN_MALL = 4; // 看商城
```

### 3.2 新增方法 `onAdViewed`
```java
/**
 * 看视频/看商城完成上报 +1 机会（OnAdViewed，带 HMAC-SHA256 sign）。
 * @param busType 2=看视频(is_view_tp_ad), 4=看商城(is_view_douyin_mall)
 */
public JSONObject onAdViewed(LotteryAuth auth, int busType) {
    long tsSec = System.currentTimeMillis() / 1000;
    String nonce = randomNonce6();  // 6 位随机小写字母
    String signStr = "silk_id=" + auth.getSilkId()
            + "&timestamp=" + tsSec
            + "&nonce=" + nonce
            + "&bus_type=" + busType;
    String sign = new HMac(HmacAlgorithm.HmacSHA256, ON_AD_VIEWED_SIGN_KEY.getBytes())
            .digestBase64(signStr);
    Map<String, Object> body = baseBody(auth);
    body.put("timestamp", tsSec);
    body.put("nonce", nonce);
    body.put("bus_type", busType);
    body.put("sign", sign);
    return postAuth(JSONObject.toJSONString(body), LOTTERY_SERVER, ON_AD_VIEWED_METHOD, auth, "onAdViewed");
}

private static String randomNonce6() {
    Random r = new Random();
    StringBuilder sb = new StringBuilder(6);
    for (int i = 0; i < 6; i++) sb.append((char) ('a' + r.nextInt(26)));
    return sb.toString();
}
```
> hutool `HMac`/`HmacAlgorithm` 已在项目依赖（`cn.hutool.crypto.digest.*`，`LotteryHttp` 已 import `MD5`，同包）。

### 3.3 新增方法 `receiveExtraLottery`
```java
/**
 * 领取累计抽奖阶梯奖（ReceiveExtraLottery）。
 * @param step 1=first_step, 2=second_step
 */
public JSONObject receiveExtraLottery(LotteryAuth auth, int step) {
    Map<String, Object> body = baseBody(auth);
    body.put("step", step);
    return postAuth(JSONObject.toJSONString(body), LOTTERY_SERVER, RECEIVE_EXTRA_LOTTERY_METHOD, auth, "receiveExtraLottery");
}
```

### 3.4 复用现有基础设施
- `postAuth` / `executeWithProxy` / `getAndroidHeaders` / `getAshe` / `getNami` 全部复用，无需改。
- 401（业务拒绝）/403（代理坏）/业务码非0（如 40043）处理沿用 `postAuth` 现有逻辑：401 返回结构化失败、403 换代理、200+code!=0 返回 JSON 由上层判断。

## 4. LotteryServiceImpl 改动

### 4.1 删除过时注释
`LotteryServiceImpl.java:53` 注释"is_view_tp_ad/is_view_douyin_mall 纯接口刷不到"删除，改为说明走 OnAdViewed。

### 4.2 runTask 流程扩展
现有流程：刷前快照 → lotteryInfo → 遍历 5 个 FLAG_TO_TYPE → 刷后快照。

**新增步骤**（插在遍历 5 任务之后、刷后快照之前）：

```java
// 3.5) 看视频/看商城（OnAdViewed，独立于 AddLotteryTimes）
addAdViewTask(items, li, auth, "is_view_tp_ad", LotteryHttp.BUS_TYPE_VIEW_TP_AD, "看视频");
addAdViewTask(items, li, auth, "is_view_douyin_mall", LotteryHttp.BUS_TYPE_VIEW_DOUYIN_MALL, "看商城");

// 3.6) 领阶梯奖（ReceiveExtraLottery）—— 刷后机会数可能变，需取最新 progress
try {
    JSONObject progress = lotteryHttp.getLotteryProgress(auth);
    JSONObject lp = progress == null ? null : progress.getJSONObject("lottery_progress");
    if (lp != null) {
        addStepPrizeTask(items, lp, auth, 1, "first", "领第一阶梯奖");
        addStepPrizeTask(items, lp, auth, 2, "second", "领第二阶梯奖");
    }
} catch (Exception e) {
    log.warn("领阶梯奖 getLotteryProgress 失败（不影响明细）: {}", e.getMessage());
}
```

### 4.3 辅助方法 `addAdViewTask`
```java
private void addAdViewTask(List<LotteryTaskResultVO.TaskItem> items, JSONObject li,
                           LotteryAuth auth, String flag, int busType, String desc) {
    LotteryTaskResultVO.TaskItem item = new LotteryTaskResultVO.TaskItem();
    item.setType(busType);  // 复用 type 字段存 bus_type
    item.setDesc(desc);
    Boolean done = li == null ? null : li.getBoolean(flag);
    if (Boolean.TRUE.equals(done)) {
        item.setStatus(SKIPPED); item.setOk(false); item.setMsg("已完成");
        items.add(item); return;
    }
    try {
        JSONObject r = lotteryHttp.onAdViewed(auth, busType);
        int code = codeOf(r);
        boolean ok = code == 0;
        item.setStatus(ok ? OK : FAIL); item.setOk(ok);
        if (!ok) item.setMsg(friendlyMsg(msgOf(r), code));
    } catch (Exception e) {
        item.setStatus(FAIL); item.setOk(false); item.setMsg(friendlyMsg(e.getMessage(), -1));
    }
    items.add(item);
}
```

### 4.4 辅助方法 `addStepPrizeTask`
```java
private void addStepPrizeTask(List<TaskItem> items, JSONObject lp, LotteryAuth auth,
                              int step, String prefix, String desc) {
    TaskItem item = new TaskItem();
    item.setType(100 + step);  // 101/102 区分阶梯奖，避免与 bus_type/type 冲突
    item.setDesc(desc);
    int count = lp.getIntValue("lottery_count");
    int stepCount = lp.getIntValue(prefix + "_step_count");
    boolean got = lp.getBoolean(prefix + "_step_prize" ...) ;  // has_got_first_step_prize / has_got_second_step_prize
    // 实际字段名 has_got_first_step_prize / has_got_second_step_prize
    if (got) { item.setStatus(SKIPPED); item.setOk(false); item.setMsg("已领取"); items.add(item); return; }
    if (count < stepCount) { item.setStatus(SKIPPED); item.setOk(false); item.setMsg("未达阶梯阈值"); items.add(item); return; }
    try {
        JSONObject r = lotteryHttp.receiveExtraLottery(auth, step);
        int code = codeOf(r);
        boolean ok = code == 0;
        item.setStatus(ok ? OK : FAIL); item.setOk(ok);
        if (!ok) item.setMsg(friendlyMsg(msgOf(r), code));  // 40043 → 友好化"阶梯奖已领取"
    } catch (Exception e) {
        item.setStatus(FAIL); item.setOk(false); item.setMsg(friendlyMsg(e.getMessage(), -1));
    }
    items.add(item);
}
```

### 4.5 friendlyMsg 新增 40043
```java
if (code == 40043) return "阶梯奖已领取";
```

### 4.6 抽取公共 codeOf/msgOf
现有遍历 5 任务的 `status`/`code` 解析逻辑重复，抽 `codeOf(JSONObject)` / `msgOf(JSONObject)` 私有方法复用，减少三处重复。

## 5. 前端 SettingsView.vue 改动（已确认无需改动 ✅）

前端仓库 `xiaocan-front-main/src/views/SettingsView.vue` 已验证：
- 明细区是 `v-for="item in br.result.tasks"` 遍历（非硬编 5 项），新增 TaskItem 自动展示。
- `taskDisplay(item)` 按 `item.status`（SKIPPED/OK/FAIL）渲染，新增 4 项完全兼容。
- **前端零改动**，本次纯后端任务。

## 6. spec 更新（task 完成阶段）
更新 `.trellis/spec/backend/xiaocan-rpc-contract.md`：
- 接口清单加 `OnAdViewed` / `ReceiveExtraLottery`。
- type→flag 映射表加 bus_type 2/4，标注走 OnAdViewed 而非 AddLotteryTimes。
- 新增 sign 算法段（HMAC-SHA256 + 密钥 + 签名串格式）。
- 删除"is_view_tp_ad/is_view_douyin_mall 纯接口刷不到"的旧表述。

## 7. 兼容性 / 回滚

- 现有 5 任务遍历逻辑不动，新增步骤独立 try，单步失败不中断。
- `type` 字段语义扩展（加 bus_type 2/4、阶梯奖 101/102），旧消费方按 `ok`/`status` 兜底不受影响。
- 回滚：还原 `LotteryHttp`（删两方法+常量）、`LotteryServiceImpl`（删新增步骤+辅助方法）、前端无改动无需回滚。

## 8. 风险

- **sign 时效**：`timestamp` 是秒级，服务端可能校验时间窗（如 ±5min）。本地时钟偏差大可能失败——但生产服务器时钟正常，且与抓包同账号同地区，风险低。
- **nonce 随机性**：`Random` 非密码学随机，但服务端只防重放（nonce+timestamp 唯一），非密码学要求，足够。
- **领阶梯奖时机**：必须在刷任务后取最新 `lottery_count`（刷视频/商城可能让 lottery_count 变化），故领阶梯奖步骤排在最后且重新取 progress。
- **40043 已领取**：服务端可能对当日已领返回 40043，记 SKIPPED 而非 FAIL。
