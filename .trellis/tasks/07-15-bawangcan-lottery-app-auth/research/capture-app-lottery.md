# 抓包结论：App 版霸王餐抽奖刷任务（Android 登录态）

> 抓包方式：手机挂代理 `192.168.225.235:8082`（mitmdump 12.2.3，CA 已装系统证书），抓小蚕 App 抽奖页。
> 抓包文件：`tmp_capture.flows`（项目根目录，约 1967+ 条流量）。
> 抓包账号：silk_id=222559356，user_id(X-Vayne)=5263106。

---

## 1. 端点与接口

**端点：`https://gwh.xiaocantech.com/rpc`**（注意是 `gwh` 不是 `gw`；mini 版用 `gw.xiaocantech.com`）。

抽奖页核心请求走 `gwh`（H5/AgentWeb 套壳页），App 原生其它业务走 `gw`。刷任务只关心 `gwh` 上的三个接口：

| methodName | serverName | body | 用途 |
|---|---|---|---|
| `SilkwormLotteryMobile.LotteryInfo` | `SilkwormLottery` | `{"silk_id":222559356}` | 查未完成任务 + 机会来源（is_view_xxx / day_num / is_add_times） |
| `SilkwormLotteryMobile.AddLotteryTimes` | `SilkwormLottery` | `{"silk_id":222559356,"type":10}` | 完成浏览任务 +1 机会（无验证，纯接口可刷） |
| `SilkwormLotteryMobile.GetLotteryProgress` | `SilkwormLottery` | `{"silk_id":222559356}` | 查机会数 lottery_count + 阶梯 first/second_step_count |

**body 无 `app_id`**（mini 版 body 带 `app_id:20`，App 版不带）。

### 分享任务前置
| methodName | serverName | body | 用途 |
|---|---|---|---|
| `InviteWordService.CreateLeaderInviteWord` | `InviteWord` | `{"silk_id":...,"leader_invite_type":0,"source":2}` | 生成分享海报/邀请文案（分享任务 type=2 前置，实现时验证是否必填） |

---

## 2. type → 任务映射（全部抓包确认，与 mini 一致）

| type | flag | 任务 | 抓包 flow | 可纯接口刷 |
|---|---|---|---|---|
| 2 | if_shared | 分享团长海报 | 2024 ✓ | ✓（可能需先 CreateLeaderInviteWord） |
| 8 | is_get_meituan_redpack | 领美团红包 | 1760 ✓ | ✓ |
| 9 | is_get_eleme_redpack | 领饿了么红包 | 1733 ✓ | ✓ |
| 10 | is_view_welfare_page | 浏览福利页 | 1345 ✓ | ✓ |
| 11 | is_view_bwc_page | 浏览霸王餐页 | （mini 已确认） | ✓ |
| — | is_view_tp_ad | 浏览广告 | 无 AddLotteryTimes | ✗ App 不走 AddLotteryTimes（WebView 计时自动标记） |
| — | is_view_douyin_mall | 浏览抖音商城 | 无 AddLotteryTimes | ✗ 同上 |

**结论：能纯接口刷的任务 = mini 版完全一致的 5 个（type 2/8/9/10/11）。** App 版的 `LotteryInfo` 虽然多返回 `is_view_tp_ad` / `is_view_douyin_mall` 标志位和 `lottery_times` 对象，但这俩任务不通过 `AddLotteryTimes` 加机会，纯接口刷不到。**App 版相对 mini 版的唯一优势是登录态更稳定（Android JWT 比 mini session 长效），任务数量与上限相同。**

---

## 3. Android 登录态 header（与 mini 版的差异核心）

抓包样本（flow 1345 AddLotteryTimes type=10）：

```
X-Platform: Android
X-Version: 3.18.3.3
X-Session-Id: 62b863d6-4a8a-4352-a6f7-40f453a0d82b
X-Sivir: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJVc2VySWQiOjUyNjMxMDYsImV4cCI6MTc4NjE0NzExM30.2DlKRCCxB47Tfsyud5seq_czrbpwANgrqozNHRU2qA0   ← JWT，必填
x-Teemo: 222559356        ← silk_id
X-Vayne: 5263106          ← user_id
X-Nami: 4f0222559356ab52   ← 每次随机生成（silk_id 嵌入）
X-Garen: 1784049752963     ← 毫秒时间戳
X-Ashe: 8ddd4c1793c98140183401f8a3b88526  ← 签名
x-City: 440111
x-Annie: XC
User-Agent: Mozilla/5.0 (Linux; Android 13; PEQM00 ... AgentWeb/5.0.8 ... xcapp;3.18.3.3;Android   ← H5 页 UA
         （原生接口用 XC;Android;3.18.3;）
```

### 与 mini 版 header 对比

| header | mini（现状 LotteryHttp.getMiniHeaders） | App（目标） |
|---|---|---|
| 端点 | `gw.xiaocantech.com` | **`gwh.xiaocantech.com`** |
| `X-Platform` | `mini` | `Android` |
| `X-Sivir` | 无 | **有 JWT（必填）** |
| `X-Session-Id` | mini session | Android session |
| `X-Version` / `version` | `3.18.3.37` | `3.18.3.3`（仅 `X-Version`，无 `version`） |
| `appid` | `20` | **无 appid header** |
| `x-City` | `0` | `440111`（账号所在城市码） |
| `x-Annie` | 无 | `XC` |
| `User-Agent` | 微信小程序 UA | `XC;Android;3.18.3;` 或 `...xcapp...` |
| body `app_id` | 有（`app_id:20`） | **无** |

### X-Sivir JWT 解析
```
header: {"alg":"HS256","typ":"JWT"}
payload: {"UserId":5263106,"exp":1786147113}   ← exp 为秒级时间戳，约 7 天有效期
```
> exp=1786147113 → 2026-08-04 左右，比 mini 的 X-Session-Id 长效得多。

---

## 4. 响应样本

### LotteryInfo（flow 1068）
```json
{
  "status": {"code": 0},
  "lottery_info": {
    "day_num": 2, "is_add_times": false, "lucky_times": 0, "is_lucky": false,
    "if_shared": false, "order_num": 0,
    "is_get_meituan_redpack": false, "is_get_eleme_redpack": false,
    "is_view_welfare_page": false, "is_view_bwc_page": true,
    "is_view_tp_ad": false, "is_view_douyin_mall": false
  },
  "lottery_times": {
    "sign_in":1, "silk_order":1, "blindbox_order":1, "meituan_redpack":1,
    "eleme_redpack":1, "view_bwc_page":1, "view_welfare_page":1,
    "view_tp_ad":1, "view_douyin_mall":1
  }
}
```
> `lottery_times` 是 App 版新增字段（mini 版没有），列出每个任务可加多少次机会，均为 1。可作为展示辅助，不参与刷任务逻辑。

### AddLotteryTimes（flow 1345, type=10）
```json
{"status": {"code": 0}}   // 成功，无业务字段
```

### GetLotteryProgress（flow 1109）
```json
{
  "status": {"code": 0},
  "lottery_progress": {
    "first_step_count": 3, "second_step_count": 9,
    "lottery_count": 0,
    "has_got_first_step_prize": false, "has_got_second_step_prize": false
  }
}
```
> `first_step_count` / `second_step_count` 是 App 版新增（阶梯抽奖），`lottery_count` 与 mini 一致。

---

## 5. 401 / 403 处理（沿用 mini 版既有逻辑）

- `401` = 业务拒绝（当日加机会次数已满 `is_add_times=false` 等），**不重试**，返回结构化失败 `{"status":{"code":401,...}}`。
- `403` = 代理被风控/封禁，`ProxyHolder.invalidate()` 换代理重试。
- `LotteryHttp.executeWithProxy` 已做此区分，App 版沿用，无需改。

---

## 6. 签名算法（与 mini 一致，无需改）

`X-Ashe = MD5(MD5((server+"."+method).toLowerCase()) + timeMillis + nami)`
`X-Nami = uuid去横线.substring(0,4) + silk_id + 剩余`（16 位 hex）

App 版签名算法和 mini 完全相同，`LotteryHttp.getAshe/getNami/generateUuid` 可直接复用，只改 header 和端点。

---

## 7. 改造关键点（给 design.md 用）

1. **端点**：`LotteryHttp.BASE_URL` 从 `gw` 改 `gwh`（或加 `BASE_URL_H5` 常量，抽奖三个接口走 `gwh`）。
2. **header**：`getMiniHeaders` → `getAndroidHeaders`，加 `X-Sivir`、`x-Annie=XC`，`X-Platform=Android`、`X-Version=3.18.3.3`、`x-City=账号城市码`，**去掉 appid**、`version`、`xweb_xhr`、`X-Model`、小程序 UA、`Referer`。
3. **body**：`baseBody` 去掉 `app_id`。
4. **登录态**：新建独立 App 登录态表（复用给项目其它部分），存 `silk_id / user_vayne / session_id / sivir(JWT) / city_code`。`LotteryAuth` 加 `sivir` 字段，`isComplete()` 要求 `silkId + sessionId + sivir`。
5. **Service**：`LotteryServiceImpl` 登录态来源从 `lottery_auth` 切到新 App 表；解析抓包头时提取 `X-Sivir`；`FLAG_TO_TYPE` 维持 2/8/9/10/11（tp_ad/douyin_mall 不加，标注释）。
6. **前端**：设置页新增 App 登录态录入（silk_id/session/sivir/城市码），用户填一次。
7. **风险**：`CreateLeaderInviteWord` 是否为 type=2 必要前置，实现时验证（先单测纯调 `AddLotteryTimes(2)`）。
