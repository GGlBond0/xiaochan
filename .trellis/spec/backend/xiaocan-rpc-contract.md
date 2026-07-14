# Xiaocan RPC Gateway Contract

> 小蚕（小蚕惠生活）后端 RPC 网关调用契约：单一端点 + header 路由 + X-Ashe 签名 + 明文 JSON。抢单/查询/抽奖刷任务等所有小蚕业务调用都走这套。

---

## 1. Scope / Trigger

调用任何小蚕业务接口（活动列表/抢单/卡券/地址/抽奖刷任务等）前必读。统一端点 `https://gw.xiaocantech.com/rpc`，**接口名不在 body，在请求 header**（`serverName` + `methodName`），body 是明文 JSON 参数。响应统一 `{"status":{"code":0,...}, ...}`，`code != 0` 即失败。

## 2. Signatures

### 签名算法（`XiaochanHttp.getAshe` / `LotteryHttp.getAshe`，已逆向破解）
```java
// timeMillis = X-Garen（当前毫秒时间戳），nami = X-Nami（16位hex，见下）
String x = MD5((serverName + "." + methodName).toLowerCase());   // 32位hex
String ashe = MD5(x + timeMillis + nami);                          // 32位hex → X-Ashe
```

### X-Nami 生成（`getNami`）
```java
// UUID 去横线后，前4位 + silk_id + 剩余位 = 16位hex
String uuid = generateUuid().replace("-", "");
String id = silkId == null ? "0" : String.valueOf(silkId);
return uuid.substring(0, 4) + id + uuid.substring(4, 20 - id.length() - 4);
```
> `generateUuid()` 模仿原始 JS：固定第14位=`4`、第19位变体位、8/13/18/23 为 `-`。

### HTTP 调用入口
- `XiaochanHttp`（Android 登录态，抢单/卡券）：`new XiaochanHttp()` 后调 public 实例方法（`grabPromotionQuota`/`getUserCardList`/`getStorePromotionDetail`/`searchAddress`...），内部走 private `postWithRes`（无登录态）或 `postWithResAuth`（带 `GrabAuth`），经 `executeWithProxy` 复用 `ProxyHolder` 代理/403重试。
- `LotteryHttp`（mini 登录态，抽奖刷任务）：同模式，`new LotteryHttp()` 后调 `lotteryInfo`/`addLotteryTimes`/`getLotteryProgress`/`userTaskV2`。

## 3. Contracts

### 请求 Header（mini，抽奖刷任务，抓包实测值）
| header | 必填 | 说明 |
|---|---|---|
| `serverName` | 是 | 服务名，如 `SilkwormLottery` / `Silkworm` / `ActivityTask` |
| `methodName` | 是 | 方法名，如 `SilkwormLotteryMobile.AddLotteryTimes` |
| `appid` | 是 | `20`（小程序） |
| `X-Ashe` | 是 | 签名（见上） |
| `X-Nami` | 是 | 16位hex |
| `X-Garen` | 是 | 毫秒时间戳 |
| `X-Session-Id` | 是 | 会话（登录态） |
| `x-Teemo` | 是 | silk_id |
| `X-Vayne` | 可空 | user_id |
| `X-Platform` | 是 | `mini`（小程序）/ `Android`（App抢单） |
| `X-Version`/`version` | 是 | `3.18.3.37` |
| `x-City` | 是 | 城市码，`0` 或如 `440111` |

### Android 登录态额外 header（抢单，`getGrabHeaders`）
`X-Sivir`（登录 JWT，`GrabAuth.sivir`）、`x-Teemo=silk_id`、`X-Vayne=userId`、`User-Agent=XC;Android;3.18.3;`、`X-Platform=Android`。

### Body
明文 JSON，无加密。通用字段 `silk_id` + `app_id:20` + 业务参数。例：
- `LotteryInfo`：`{"silk_id":222559356,"app_id":20}`
- `AddLotteryTimes`：`{"silk_id":222559356,"type":11,"app_id":20}`
- `GrabPromotionQuota`：`{"silk_id":...,"promotion_id":...,"latitude":...,"longitude":...,"city_code":...,"store_platform":1,"if_advance_order":false}`

### 响应
```json
{"status":{"code":0}, ...业务字段...}
```
`code != 0` = 业务失败，`status.msg` 为错误信息（中文，UTF-8，注意旧抓包工具可能截断成乱码但实际是 UTF-8）。

## 4. Validation & Error Matrix

| 情况 | HTTP 状态 | 处理 |
|---|---|---|
| 正常 | 200，`status.code==0` | 读业务字段 |
| 业务失败 | 200，`status.code!=0` | 读 `status.msg`，按业务处理（如抽奖 `code:200001` = 需风控验证） |
| **代理被封/风控** | **403** | 换代理重试（`ProxyHolder.invalidate()`） |
| **业务拒绝（如当日次数已满）** | **401**，body 空 | **不重试**，直接返回结构化失败 |
| 网络异常 | - | `ProxyHolder.invalidate()` 换代理重试 |
| 代理未启用 | - | 直连 |

### Gotcha: 401 ≠ 代理问题，别换代理重试

> **Warning**: 小蚕网关对**业务上限/权限不足**返回 `401`（body 空，网关层直接拒，不进业务逻辑）。例：当日加抽奖次数已满（`LotteryInfo.is_add_times=false`）时再调 `AddLotteryTimes` 全部 401。
>
> `401` 是**业务拒绝**不是**代理坏**。若和 `403` 一样 `invalidate()` 换代理重试，会白白消耗代理额度且最终仍 401。
>
> **正确**：`executeWithProxy` 里 401 直接 `return response`，上层把 401 转成结构化失败（`{"status":{"code":401,...}}`）返回给业务层记录，不抛异常中断整轮。
>
> **反模式**：把 401 当 403 一样 `invalidate + continue` 重试 → 代理池被空耗、刷任务全程失败。`LotteryHttp.executeWithProxy` 已做此区分。

### Gotcha: 抽奖执行被腾讯防水墙挡

> **Warning**: 执行抽奖 `SilkwormLotteryMobile.Lottery` 前置 `Brs.RiskCheckService.Verify`（腾讯防水墙 TCaptcha，需 `ticket/rand_str/check_sum` 行为验证票据，有时效性）。纯接口重放 ticket 会 `invalid captcha`。**加机会（`AddLotteryTimes`）无验证可纯接口刷；执行抽奖无法纯接口绕过，需手动过滑块。**

## 5. Good/Base/Bad Cases

- **Good**：`LotteryInfo`/`GetLotteryProgress` 返回 `code:0`，读 `lottery_info.day_num`/`lottery_progress.lottery_count`。
- **Base**：`AddLotteryTimes` `is_add_times=true` 时返回 `code:0`，`day_num` +1，`lottery_count` 上涨。
- **Bad**：`is_add_times=false`（当日已满）时 `AddLotteryTimes` 全部 401；`Lottery`（执行抽奖）返回 `code:200001` 需风控验证。

## 6. Tests Required

- 签名一致性：复刻 `getAshe`/`getNami` 调只读接口（如 `LotteryInfo`）必须返回 `code:0`，证明签名+登录态正确。
- 401 不重试：模拟 `AddLotteryTimes` 返回 401，断言 `executeWithProxy` 不调 `ProxyHolder.invalidate()`、不重试、返回结构化失败。
- 零侵入：`LotteryHttp` 不引用 `GrabAuth`/`XiaochanHttp` 既有签名（抢单核心隔离）。

## 7. Wrong vs Correct

### Wrong：401 当 403 重试
```java
if (response.getStatus() == 403 || response.getStatus() == 401) {
    ProxyHolder.invalidate();  // 401 是业务上限，换代理毫无意义，白耗代理额度
    continue;
}
```

### Correct：401 直接返回
```java
if (response.getStatus() == 403) { ProxyHolder.invalidate(); continue; }  // 代理坏
if (response.getStatus() == 401) { return response; }                      // 业务拒，不重试
```

---

## 接口清单（抓包确认）

### 抽奖刷任务（SilkwormLottery，mini 登录态）
| methodName | body | 用途 |
|---|---|---|
| `SilkwormLotteryMobile.LotteryInfo` | {silk_id,app_id} | 查机会来源（is_view_xxx 未完成项 + day_num + is_add_times） |
| `SilkwormLotteryMobile.AddLotteryTimes` | {silk_id,type,app_id} | 完成浏览任务 +1 机会（无验证） |
| `SilkwormLotteryMobile.GetLotteryProgress` | {silk_id,app_id} | 查 lottery_count |
| `SilkwormLotteryMobile.Lottery` | {silk_id,prize_type,app_id} | 执行抽奖（被 TCaptcha 挡） |
| `Brs.RiskCheckService.Verify` | {silk_id,ticket,rand_str,check_sum,service_name,platform,app_id} | 抽奖前置风控验证 |

### AddLotteryTimes type → 任务映射（type-map）
| type | flag | 任务 |
|---|---|---|
| 2 | if_shared | 分享 |
| 8 | is_get_meituan_redpack | 领美团红包 |
| 9 | is_get_eleme_redpack | 领饿了么红包 |
| 10 | is_view_welfare_page | 浏览福利页 |
| 11 | is_view_bwc_page | 浏览霸王餐页 |
| 待补 | is_view_tp_ad | 浏览广告 |
| 待补 | is_view_douyin_mall | 浏览抖音商城 |

### 抢单/查询（Silkworm，Android 登录态，见 XiaochanHttp）
`RecService.GetStorePromotionList` / `RecService.SearchStorePromotionList` / `SilkwormService.GetStorePromotionDetail` / `SilkwormService.GrabPromotionQuota` / `SilkwormCardService.GetUserCardList` / `SilkwormLbsService.Suggestion`

---

## Design Decision: mini 登录态独立于 GrabAuth

**Context**：抽奖刷任务走电脑微信小程序（mini 登录态，`X-Platform=mini`），抢单走 Android App（`GrabAuth`，强依赖 `X-Sivir` JWT）。mini 登录态无 `X-Sivir`，`GrabAuth.isComplete()` 要求 `X-Sivir` 会判不过。

**Decision**：**不动 `GrabAuth`/`grab_login_state`**，为抽奖刷任务新建 `LotteryAuth` POJO + `lottery_auth` 表 + `LotteryHttp`。两套登录态完全隔离，避免改抢单核心 `isComplete` 语义引发回归。

**Why not 复用**：`GrabAuth.isComplete()` 校验 `sivir+userId+sessionId`，mini 无 `sivir`；改 `GrabAuth` 让它兼容两套会触动抢单所有调用点，风险远大于新建一套。

**How to extend**：未来若新增其它小蚕登录态来源（如新小程序），优先新建独立 `XxxAuth` + 表 + `Http`，复用 `XiaochanHttp.getAshe/getNami` 签名算法（已 static 可参照复制），而非改既有 auth 类。
