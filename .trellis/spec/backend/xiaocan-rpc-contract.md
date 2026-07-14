# Xiaocan RPC Gateway Contract

> 小蚕（小蚕惠生活）后端 RPC 网关调用契约：单/双端点 + header 路由 + X-Ashe 签名 + 明文 JSON。抢单/查询/抽奖刷任务等所有小蚕业务调用都走这套。
>
> **2026-07-15 更新**：抽奖刷任务已从微信小程序（mini）登录态改为小蚕 App（Android）登录态。端点 `gw`→`gwh`，body 去 `app_id`，header 换 Android + `X-Sivir` JWT。mini 版**已废弃**（见末尾历史段）。

---

## 1. Scope / Trigger

调用任何小蚕业务接口（活动列表/抢单/卡券/地址/抽奖刷任务等）前必读。**两个端点**：
- `https://gw.xiaocantech.com/rpc` —— App 原生业务（抢单/查询/卡券/任务 `UserTaskV2` 等）
- `https://gwh.xiaocantech.com/rpc` —— App H5 抽奖页（`LotteryInfo`/`AddLotteryTimes`/`GetLotteryProgress`）

**接口名不在 body，在请求 header**（`serverName` + `methodName`），body 是明文 JSON 参数。响应统一 `{"status":{"code":0,...}, ...}`，`code != 0` 即失败。

## 2. Signatures

### 签名算法（`XiaochanHttp.getAshe` / `LotteryHttp.getAshe`，已逆向破解，mini/App 通用）
```java
// timeMillis = X-Garen（当前毫秒时间戳），nami = X-Nami（16位hex，见下）
String x = MD5((serverName + "." + methodName).toLowerCase());   // 32位hex
String ashe = MD5(x + timeMillis + nami);                          // 32位hex → X-Ashe
```

### X-Nami 生成（`getNami`，每次请求随机，App 版不存表）
```java
// UUID 去横线后，前4位 + silk_id + 剩余位 = 16位hex
String uuid = generateUuid().replace("-", "");
String id = silkId == null ? "0" : String.valueOf(silkId);
return uuid.substring(0, 4) + id + uuid.substring(4, 20 - id.length() - 4);
```
> `generateUuid()` 模仿原始 JS：固定第14位=`4`、第19位变体位、8/13/18/23 为 `-`。
> App 版 X-Nami 每次请求随机生成，**不存数据库**（mini 版曾存 `lottery_auth.nami`，已删该列）。合并后 `login_state.nami` 仅抢单登录态可选留底，霸王餐登录态该列为空。

### HTTP 调用入口
- `XiaochanHttp`（Android 登录态，抢单/卡券）：`new XiaochanHttp()` 后调 public 实例方法（`grabPromotionQuota`/`getUserCardList`/`getStorePromotionDetail`/`searchAddress`...），内部走 private `postWithRes`（无登录态）或 `postWithResAuth`（带 `GrabAuth`），经 `executeWithProxy` 复用 `ProxyHolder` 代理/403重试。端点 `gw`。
- `LotteryHttp`（**App / Android 登录态，抽奖刷任务**）：`new LotteryHttp()` 后调 `lotteryInfo`/`addLotteryTimes`/`getLotteryProgress`/`userTaskV2`，带 `LotteryAuth`（含 `X-Sivir`），端点 **`gwh`**，body **无 app_id**。

## 3. Contracts

### 请求 Header（App / Android，抽奖刷任务，`LotteryHttp.getAndroidHeaders`，2026-07-15 抓包实测）
| header | 必填 | 说明 |
|---|---|---|
| `serverName` | 是 | 服务名，抽奖为 `SilkwormLottery` |
| `methodName` | 是 | 方法名，如 `SilkwormLotteryMobile.LotteryInfo` |
| `X-Ashe` | 是 | 签名（见上） |
| `X-Nami` | 是 | 16位hex，每次随机生成 |
| `X-Garen` | 是 | 毫秒时间戳 |
| `X-Platform` | 是 | `Android` |
| `X-Version` | 是 | `3.18.3.3`（仅 `X-Version`，无 `version`） |
| `x-Annie` | 是 | `XC` |
| `X-Session-Id` | 是 | 会话（App 登录态） |
| `x-Teemo` | 是 | silk_id |
| `X-Vayne` | 可空 | user_id |
| `X-Sivir` | 是 | **Android 登录 JWT，必填** |
| `x-City` | 可空 | 城市码，如 `440111`（从抓包解析存 `login_state.city_code`，霸王餐用；抢单登录态该字段为空，`LotteryHttp` 对 null 容错不加此 header） |
| `User-Agent` | 是 | `XC;Android;3.18.3;`（原生接口） |
| `Content-Type` | 是 | `application/json; charset=utf-8` |
| **`appid`** | **无** | App 版**无 appid header**（mini 版有 `appid:20`） |

> **App 抽奖页部分请求** UA 是 `...AgentWeb...xcapp;3.18.3.3;Android`（H5 套壳页）。刷任务后端用 `XC;Android;3.18.3;` 即可。

### Android 登录态额外 header（抢单，`getGrabHeaders`，端点 gw）
`X-Sivir`（JWT，`GrabAuth.sivir`）、`x-Teemo=silk_id`、`X-Vayne=userId`、`User-Agent=XC;Android;3.18.3;`、`X-Platform=Android`、`X-Version=3.18.3.3`。

### Body（App 版抽奖刷任务，无加密，**无 app_id**）
明文 JSON，通用字段仅 `silk_id`（**不带 `app_id`**）。例：
- `LotteryInfo`：`{"silk_id":222559356}`
- `AddLotteryTimes`：`{"silk_id":222559356,"type":10}`
- `GetLotteryProgress`：`{"silk_id":222559356}`
- `GrabPromotionQuota`（抢单，端点 gw，带登录态）：`{"silk_id":...,"promotion_id":...,"latitude":...,"longitude":...,"city_code":...,"store_platform":1,"if_advance_order":false}`

### 响应
```json
{"status":{"code":0}, ...业务字段...}
```
`code != 0` = 业务失败，`status.msg` 为错误信息（中文 UTF-8）。

App 版 `LotteryInfo` 额外返回 `lottery_times` 对象（各任务加几次机会，均 1，展示辅助）；`GetLotteryProgress` 额外返回 `first_step_count`/`second_step_count`（阶梯抽奖）。

## 4. Validation & Error Matrix

| 情况 | HTTP 状态 | 处理 |
|---|---|---|
| 正常 | 200，`status.code==0` | 读业务字段 |
| 业务失败 | 200，`status.code!=0` | 读 `status.msg`，runTask 转 `TaskItem.ok=false` 不中断（如 `code:40040`="浏览福利页只能一次"） |
| **代理被封/风控** | **403** | 换代理重试（`ProxyHolder.invalidate()`） |
| **业务拒绝（当日次数已满/权限不足）** | **401**，body 空 | **不重试**，直接返回结构化失败 `{"status":{"code":401,...}}` |
| 网络异常（SocketTimeout 等） | - | `ProxyHolder.invalidate()` 换代理重试 |
| 代理未启用 | - | 直连 |
| **JWT 过期**（`X-Sivir` 失效） | 401 | 同 401 路径，提示用户重新录入 App 登录态 |

> App 版 `AddLotteryTimes` 对**已做过的任务**实测返回 **HTTP 200 + `code:40040`**（业务码，非 HTTP 401）。`is_add_times=false`（当日加机会次数已满）才走 HTTP 401。两者均"不重试、不中断"。

### Gotcha: 401 ≠ 代理问题，别换代理重试

> **Warning**: 小蚕网关对**业务上限/权限不足/JWT 过期**返回 `401`（body 空，网关层直接拒）。例：当日加抽奖次数已满（`LotteryInfo.is_add_times=false`）时再调 `AddLotteryTimes` 走 401。
>
> `401` 是**业务拒绝**不是**代理坏**。若和 `403` 一样 `invalidate()` 换代理重试，会白白消耗代理额度且最终仍 401。
>
> **正确**：`executeWithProxy` 里 401 直接 `return response`，上层把 401 转成结构化失败（`{"status":{"code":401,...}}`）返回给业务层记录，不抛异常中断整轮。`LotteryHttp.executeWithProxy` 已做此区分（403 换代理、401 直接返回）。
>
> **反模式**：把 401 当 403 一样 `invalidate + continue` 重试 → 代理池被空耗、刷任务全程失败。

### Gotcha: 抽奖执行被腾讯防水墙挡

> **Warning**: 执行抽奖 `SilkwormLotteryMobile.Lottery` 前置 `Brs.RiskCheckService.Verify`（腾讯防水墙 TCaptcha，需 `ticket/rand_str/check_sum` 行为验证票据，有时效性）。纯接口重放 ticket 会 `invalid captcha`。**加机会（`AddLotteryTimes`）无验证可纯接口刷；执行抽奖无法纯接口绕过，需手动过滑块。**

### Gotcha: 端点 gw vs gwh

> **Warning**: App 抽奖页核心三接口（`LotteryInfo`/`AddLotteryTimes`/`GetLotteryProgress`）走 **`gwh.xiaocantech.com`**（H5 页），不是 `gw`。`UserTaskV2` 等其它任务接口走 `gw`。`LotteryHttp` 统一用 `gwh`（本模块只调抽奖三接口）；若未来加 `UserTaskV2` 需走 `gw`，要按接口分端点，不能一刀切。

## 5. Good/Base/Bad Cases

- **Good**：App 登录态 + `gwh` + `LotteryInfo` 返回 `code:0`，读 `lottery_info.day_num`/`lottery_progress.lottery_count`。
- **Base**：`AddLotteryTimes` 未完成任务时返回 `code:0`，`day_num` +1，`lottery_count` 上涨。
- **Bad**：已做过的任务 `AddLotteryTimes` 返回 `code:40040`（业务码）；`is_add_times=false` 当日已满走 HTTP 401；`Lottery`（执行抽奖）返回 `code:200001` 需风控验证。

## 6. Tests Required

- 签名一致性：复刻 `getAshe`/`getNami` 调只读接口（如 `LotteryInfo`，端点 gwh + Android header + 真实 `X-Sivir`）必须返回 `code:0`，证明签名+登录态+端点正确（2026-07-15 已实测）。
- 401 不重试：模拟 `AddLotteryTimes` 返回 401，断言 `executeWithProxy` 不调 `ProxyHolder.invalidate()`、不重试、返回结构化失败。
- 业务码非0 不中断：`AddLotteryTimes` 返回 `code:40040`，断言 runTask `TaskItem.ok=false`、继续后续任务、不抛异常。
- 零侵入：`LotteryHttp` 不引用 `GrabAuth`/`XiaochanHttp` 既有签名（抢单核心隔离）。

## 7. Wrong vs Correct

### Wrong：401 当 403 重试
```java
if (response.getStatus() == 403 || response.getStatus() == 401) {
    ProxyHolder.invalidate();  // 401 是业务上限/JWT 过期，换代理毫无意义
    continue;
}
```

### Correct：401 直接返回，业务码非0 转 TaskItem.ok=false
```java
if (response.getStatus() == 403) { ProxyHolder.invalidate(); continue; }  // 代理坏
if (response.getStatus() == 401) { return response; }                      // 业务拒/JWT 过期，不重试
// 200 但 status.code!=0（如 40040）：上层 item.ok=false, msg=status.msg，继续下一任务
```

### Wrong：抽奖接口端点用 gw / body 带 app_id（mini 旧写法）
```java
private static final String BASE_URL = "https://gw.xiaocantech.com/rpc";  // 抽奖走 gwh
body.put("app_id", 20);  // App 版无 app_id
```

### Correct：App 抽奖走 gwh、body 无 app_id、header 带 X-Sivir
```java
private static final String BASE_URL = "https://gwh.xiaocantech.com/rpc";
body.put("silk_id", auth.getSilkId());  // 仅此
headers.put("X-Platform", "Android");
headers.put("X-Sivir", auth.getSivir());  // 必填
```

---

## 接口清单（抓包确认）

### 抽奖刷任务（SilkwormLottery，App / Android 登录态，端点 gwh）
| methodName | body | 用途 |
|---|---|---|
| `SilkwormLotteryMobile.LotteryInfo` | {silk_id} | 查机会来源（is_view_xxx 未完成项 + day_num + is_add_times + lottery_times） |
| `SilkwormLotteryMobile.AddLotteryTimes` | {silk_id,type} | 完成浏览任务 +1 机会（无验证） |
| `SilkwormLotteryMobile.GetLotteryProgress` | {silk_id} | 查 lottery_count + 阶梯 first/second_step_count |
| `SilkwormLotteryMobile.Lottery` | {silk_id,prize_type} | 执行抽奖（被 TCaptcha 挡，本模块不调） |
| `SilkwormLotteryMobile.IsShowStepLottery` | {silk_id} | 是否显示阶梯抽奖（展示） |
| `InviteWordService.CreateLeaderInviteWord` | {silk_id,leader_invite_type,source} | 分享海报前置（type=2 前置，实现时验证是否必要） |

### AddLotteryTimes type → 任务映射（App 抓包确认，与 mini 一致）
| type | flag | 任务 | 可纯接口刷 |
|---|---|---|---|
| 2 | if_shared | 分享团长海报 | ✓（可能需先 CreateLeaderInviteWord） |
| 8 | is_get_meituan_redpack | 领美团红包 | ✓ |
| 9 | is_get_eleme_redpack | 领饿了么红包 | ✓ |
| 10 | is_view_welfare_page | 浏览福利页 | ✓ |
| 11 | is_view_bwc_page | 浏览霸王餐页 | ✓ |
| — | is_view_tp_ad | 浏览广告 | ✗ App 不走 AddLotteryTimes（WebView 计时自动标记） |
| — | is_view_douyin_mall | 浏览抖音商城 | ✗ 同上 |

> **App 版与 mini 版能刷的任务完全相同（5 个 type）**。App 版优势仅在登录态更长效（`X-Sivir` JWT，exp≈7 天，比 mini session 长效）。

### 抢单/查询（Silkworm，Android 登录态，端点 gw，见 XiaochanHttp）
`RecService.GetStorePromotionList` / `RecService.SearchStorePromotionList` / `SilkwormService.GetStorePromotionDetail` / `SilkwormService.GrabPromotionQuota` / `SilkwormCardService.GetUserCardList` / `SilkwormLbsService.Suggestion`

---

## Design Decision: 登录态统一池 login_state（2026-07-15 合并）

**Context**：抢单与霸王餐刷任务用的是**同一种**小蚕 App 账号登录态（一组抓包 header：X-Sivir JWT / X-Session-Id / X-Vayne / X-Teemo / X-Nami），历史上却拆成 `grab_login_state` 与 `lottery_auth` 两表、两套字段名（`xc_sivir` vs `sivir` 等）、两套重复解析逻辑。这是项目混乱根源。2026-07-15 task `07-15-login-state-unified-mgmt` 合并为单池 `login_state`。

**Decision**：新建 `login_state` 单表（统一字段 `sivir/session_id/user_vayne/silk_id/nami/city_code/location_id/expire_at/raw_headers`，全部业务专属字段可空），抢单/霸王餐/卡券查询都从这一池用选择框引用。`LoginStateService` 合并两处解析逻辑，提供 `getEntity`（HTTP 上下文）/`getEntityByIdAndUser`（定时任务无 HTTP 上下文）/`toGrabAuth`/`toLotteryAuth`。前端新增 `/login-state` 管理页，`SettingsView` 霸王餐录入段下线改选已有登录态。

**迁移关键**：抢单行 `INSERT INTO login_state(id,...) SELECT id,... FROM grab_login_state` **保持原 id 不变**，使 `grab_config.login_state_id` 无需改值即继续指向新表；霸王餐行新分配 id（无外部引用）。`GrabJwtExpireTask` 改扫 `login_state`（覆盖抢单+霸王餐）。`LotteryHttp` 对 `cityCode==null` 容错（不加 x-City header），故霸王餐可用迁入的抢单登录态。

**Why 推翻原"独立表物理隔离"决策**：原决策（见下方历史段）以"风险隔离"为由拆两表，但两套表+解析是重复维护负担，且 `LotteryAuth` 注释本就写"方便复用"却从未复用。合并后登录态一处录入、多处选择框引用，才真正减少混乱。

**How to extend**：未来若新增其它小蚕登录态来源（新端点/header 组合），优先在 `login_state` 加可空业务专属列 + 在 `LoginStateService` 加 `toXxxAuth` 适配方法，而非新建独立表。复用 `XiaochanHttp.getAshe/getNami` 签名算法。

---

## 历史：App 登录态独立表（lottery_auth，已被 2026-07-15 合并取代）

> **2026-07-15 前**的决策曾为"独立表物理隔离"：`lottery_auth` 与 `grab_login_state` 各自独立表，`LotteryAuth` POJO `isComplete()` 要求 `silkId+sessionId+sivir`，`LotteryHttp` 走 `gwh` + Android header + body 无 app_id。**该决策已被上方"登录态统一池"取代**——两表已合并入 `login_state`，旧表/旧接口保留至验证通过后删除（阶段3）。`LotteryHttp` 的端点/header/body 契约**不变**，只是登录态来源从 `lottery_auth` 改读 `login_state`。保留此段作历史对照。

---

## 历史：mini 版（已废弃，2026-07-15 前的版本）

> mini 版抽奖刷任务走电脑微信小程序抓包，`X-Platform=mini`、`appid:20` header、body 带 `app_id:20`、端点 `gw.xiaocantech.com`、登录态无 `X-Sivir`、`lottery_auth` 表存 `nami` 列。**已全部删除**，代码切到 App 版。保留此段作历史对照，未来勿再用 mini 版写法。

---

## Scenario: 刷任务结果契约 LotteryTaskResultVO（2026-07-15 task `07-15-lottery-task-run-log`）

### 1. Scope / Trigger

`POST /api/lottery/run?authId=<id>`（`LotteryController.runTask` → `LotteryServiceImpl.runTask`）的响应结构变更：`LotteryTaskResultVO.TaskItem` 增加 `status` 枚举字段，失败原因在后端友好化。触发跨层契约更新（前端 `SettingsView.vue` 完成明细区按 status 渲染）。

### 2. Signatures

```java
// VO
LotteryTaskResultVO {
  String authName;
  Integer beforeCount, afterCount;      // GetLotteryProgress.lottery_count（刷前/刷后快照，失败为 null）
  Integer beforeDayNum, afterDayNum;    // LotteryInfo.day_num
  List<TaskItem> tasks;                  // 全部 5 个任务的逐条记录（不再跳过已完成的）
  String error;                          // 顶层错误（友好化文案），非 null 表示整体性失败
}

enum TaskStatus { SKIPPED, OK, FAIL }   // 已完成跳过 / 本次成功 / 本次失败

TaskItem {
  Integer type;          // AddLotteryTimes.type
  String desc;           // 任务描述
  TaskStatus status;     // 执行状态（新增，主语义）
  Boolean ok;            // = (status == OK)，保留兼容旧消费方
  String msg;            // 原因：SKIPPED="已完成"；FAIL=友好化原因；OK 留空
}
```

### 3. Contracts

- `tasks` **始终含全部 5 个 flag 对应的 TaskItem**（分享/领美团红包/领饿了么红包/浏览福利页/浏览霸王餐页），不再因已完成而省略。
- `status` 由后端权威赋值；前端按 `ok` 降级兜底（无 status 时 `ok==true→成功`、`ok==false&&msg→失败`、否则→已完成）。
- `msg` 失败原因经后端 `friendlyMsg(raw, code)` 友好化（见下错误矩阵），**不再透传裸 `状态码错误:-1`**。

### 4. Validation & Error Matrix（friendlyMsg 映射，2026-07-15 新增）

| 触发 | code | 友好文案（msg/error） |
|---|---|---|
| 当日加机会次数已满/权限不足 | 401 | 当日加机会次数已满或权限不足 |
| 代理 403 或超时（`状态码错误:-1`/`状态码错误:403`） | -1 | 代理不可用（403 或超时），请更换代理后重试 |
| 代理池为空（`代理不可用`） | -1 | 代理池为空，请配置代理后重试 |
| 登录态不完整 | -1 | 登录态不完整，请补全 silk_id/X-Session-Id/X-Sivir |
| 其他 | — | 原文透传 |

### 5. Good/Base/Bad Cases

- **Good**：未完成任务 `addLotteryTimes` 返回 `code:0` → `status=OK`，对应行前端绿色"成功"。
- **Base**：任务已完成 → `status=SKIPPED, msg="已完成"`，前端灰色"已完成"（不显示 msg，避免与状态标签重复）。
- **Bad**：`addLotteryTimes` 抛 `状态码错误:-1`（代理全挂）→ 该任务 `status=FAIL, msg=友好化`；若 `lotteryInfo` 都失败 → 顶层 `error` 友好化 + `tasks` 空 + return；若仅快照 `getLotteryProgress` 失败 → 快照字段 null（前端显示"—"），`tasks` 已填不丢。

### 6. Tests Required

- 全完成场景：`lottery_info` 5 个 flag 全 true → `tasks` 含 5 个 `SKIPPED`（断言 size==5、全 SKIPPED）。
- 快照失败不丢明细：`getLotteryProgress` 抛异常 → `beforeCount/afterCount` null 且 `tasks` 仍填充（断言独立 try 不中断遍历）。
- 顺序正确性：`beforeCount` 必须取自遍历**之前**的 `getLotteryProgress`（断言 `beforeCount` 反映 pre-run、`afterCount` 反映 post-run，机会变化非恒 0）。
- 友好化：代理失败时 `msg`/`error` 不含裸 `状态码错误:-1`（断言含"代理不可用"）。

### 7. Wrong vs Correct

#### Wrong：刷前快照排在遍历之后
```java
// 遍历 addLotteryTimes 先执行，再取 beforeCount → beforeCount 实为 post-run 值
for (...) { addLotteryTimes(...); }           // 机会已 +N
vo.setBeforeCount(getLotteryProgress(...));   // 此时已是刷后值，机会变化恒 ~0，"刷前机会数"标签失真
```

#### Correct：刷前快照在遍历前，各阶段独立 try
```java
try { vo.setBeforeCount(getLotteryProgress(...)); } catch (e) { log.warn(...); }  // 刷前（失败只丢统计）
try { lotteryInfo...; } catch (e) { vo.setError(friendlyMsg(...)); return vo; }    // 核心失败直接返回
for (...) { /* 填 items */ }
try { vo.setAfterCount(getLotteryProgress(...)); } catch (e) { log.warn(...); }   // 刷后（失败只丢统计）
```

---

## Design Decision: 刷任务执行顺序与独立 try 鲁棒性（2026-07-15）

**Context**：`runTask` 原顺序 刷前 getLotteryProgress → lotteryInfo → 遍历 addLotteryTimes → 刷后 getLotteryProgress。代理不稳定时，刷前快照排在 lotteryInfo 之前，第一步全挂就直接抛异常到外层 catch，**走不到遍历**，`tasks` 为空——用户只看到顶层 error，看不到任何任务明细（AC4 违背）。

**Options**：
1. 遍历前置、快照后置（但刷前快照也被推到遍历后 → 语义错，见 Wrong/Correct）。
2. 各阶段独立 try + 刷前快照仍在遍历前（最终采用）。

**Decision**：选 2。顺序 = **刷前快照(独立 try) → lotteryInfo(独立 try,失败即 error+return) → 遍历填 items → 刷后快照(独立 try)**。刷前/刷后快照各用独立 try，失败只 `log.warn` 不抛出；lotteryInfo 失败设 `error` 并 return（无明细可填）；遍历内每任务 `addLotteryTimes` 再各自 try。这样保证：① `beforeCount`/`afterCount` 语义真实；② 只要 lotteryInfo 通，`tasks` 必填（快照失败不丢明细）；③ 单任务失败不中断后续。

**Why**：刷任务对用户的核心价值是"逐任务执行明细"，统计数字(机会数)是次要辅助。代理不稳定时宁可丢统计数字也要保明细。独立 try 让每个外部调用故障的影响最小化到自身字段。

**How to extend**：未来若新增其它需快照对比的刷类任务，沿用"核心遍历用独立 try 包住、统计快照用独立 try 不中断"模式；友好化原因集中在后端 `friendlyMsg`，前端只渲染，避免前后端重复维护映射表。
