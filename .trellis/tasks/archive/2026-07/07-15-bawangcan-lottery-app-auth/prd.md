# 霸王餐抽奖刷任务改 App 版（Android 登录态）

## Goal

将"霸王餐抽奖刷浏览任务攒抽奖机会"模块从微信小程序（mini）登录态改为小蚕 App（Android）登录态。用户在系统里填一次 Android 登录态后，后端用 Android 登录态调用抽奖刷任务接口，自动完成浏览/分享/领红包类任务，攒够抽奖机会。Android 登录态以独立表存储，方便项目其它部分（如未来抢单/查询复用）共享，不重新填写。

## Background

- 现状：抽奖刷任务走 mini 登录态（`LotteryHttp` + `LotteryAuth` + `lottery_auth` 表），端点 `gw.xiaocantech.com`，header `X-Platform=mini`、body 带 `app_id:20`，登录态无 `X-Sivir`。
- 问题：mini 登录态来源是电脑微信小程序抓包，session 短效、易失效，且与抢单的 Android 登录态隔离，用户要分别维护两套登录态。
- 抓包结论（详见 `research/capture-app-lottery.md`）：App 版抽奖接口名/body/签名/type 映射与 mini 完全一致，仅端点（`gwh`）、header（Android + `X-Sivir` JWT）、body（无 `app_id`）不同。能纯接口刷的任务仍是 type 2/8/9/10/11 五个（`is_view_tp_ad`/`is_view_douyin_mall` 在 App 端也不走 `AddLotteryTimes`，刷不到，与 mini 一致）。
- 路线：采用路线 A —— 改造现有 `LotteryHttp`/`LotteryAuth`/`LotteryServiceImpl` 切到 Android 登录态，登录态新建独立表（不复用 `grab_login_state`，避免改抢单核心 auth 语义）。

## Requirements

### 功能需求
1. 新建独立的 App（Android）登录态存储，记录字段：名称、silk_id、user_id(X-Vayne)、session_id、sivir(JWT)、city_code、原始抓包头。同系统用户下同 silk_id 去重，支持增删改查。
2. 后端刷任务流程改用 Android 登录态调用 `gwh.xiaocantech.com/rpc` 的 `SilkwormLotteryMobile.LotteryInfo / AddLotteryTimes / GetLotteryProgress`：
   - 请求 header：`X-Platform=Android`、`X-Sivir`(JWT)、`X-Session-Id`、`x-Teemo`(silk_id)、`X-Vayne`(user_id)、`X-Version=3.18.3.3`、`x-City`、`x-Annie=XC`，无 `appid`。
   - 请求 body：仅 `silk_id`（加机会多 `type`），**不带 `app_id`**。
   - 端点用 `gwh.xiaocantech.com`。
3. 刷任务逻辑：查 `LotteryInfo` 未完成项 → 对 type 2/8/9/10/11 逐个 `AddLotteryTimes(type)` → `GetLotteryProgress` 对比前后 `lottery_count`。`is_view_tp_ad`/`is_view_douyin_mall` 不刷（标注说明：App 端不走 AddLotteryTimes，纯接口刷不到）。
4. 401（业务拒绝/当日已满）不重试、返回结构化失败；403（代理风控）换代理重试 —— 沿用 mini 版既有逻辑，不改。
5. 前端设置页新增 App 登录态录入入口：粘贴抓包头后自动解析出 silk_id/session_id/sivir/user_id/城市码，保存到新表。
6. mini 版抽奖刷任务模块**移除或停用**（切换到 App 版后 mini 那套 `lottery_auth` 表/接口不再使用，避免双套混乱）。

### 约束
- 不改抢单核心 `GrabAuth`/`grab_login_state`/`XiaochanHttp` 的既有语义与调用点。
- 签名算法 `getAshe/getNami/generateUuid` 复用现有实现，不重写。
- 登录态完整性校验：`silk_id + session_id + sivir` 三者必填，缺一报错（与 mini 的 `silk_id + session_id` 不同）。
- `CreateLeaderInviteWord` 是否为 type=2（分享）必要前置需实现时验证；若纯调 `AddLotteryTimes(2)` 即可加机会，则不依赖它。

### 非目标
- 不实现"执行抽奖"（`SilkwormLotteryMobile.Lottery`，被腾讯防水墙挡，需手动过滑块，本次不碰）。
- 不刷 `is_view_tp_ad`/`is_view_douyin_mall`（App 端纯接口刷不到）。
- 不改抢单/查询/卡券等其它小蚕业务模块。

## Acceptance Criteria

- [ ] 新建 App 登录态表（DDL + Entity + Mapper），字段含 silk_id/user_vayne/session_id/sivir/city_code/raw_headers，同用户同 silk_id 去重。
- [ ] `LotteryHttp` 切到 Android header + `gwh` 端点 + body 无 app_id；签名/代理/401/403 逻辑不变。
- [ ] `LotteryAuth` 加 `sivir` 字段，`isComplete()` 要求 silk_id+session_id+sivir。
- [ ] `LotteryServiceImpl` 登录态来源切到新 App 表，`FLAG_TO_TYPE` 维持 2/8/9/10/11；刷前/刷后快照正确返回 `lottery_count` 变化。
- [ ] 前端设置页可粘贴抓包头自动解析并保存 App 登录态；列表/删除可用。
- [ ] mini 版抽奖模块停用（`lottery_auth` 表/接口移除或下线，不再被前端调用）。
- [ ] 用真实 Android 登录态实测：刷一次任务，未完成项全部 `code:0`，`lottery_count` 上涨；当日已满时 401 不重试、不抛异常中断。
- [ ] spec `xiaocan-rpc-contract.md` 更新 App 版抽奖接口契约（端点/header/body/type 映射/与 mini 差异）。

## Notes

- 抓包证据见 `research/capture-app-lottery.md`（端点、header、body、响应样本、type 映射、JWT 时效）。
- 复杂任务：需 `design.md`（技术设计：新表结构、LotteryHttp/LotteryAuth/LotteryServiceImpl 改造、前后端契约）+ `implement.md`（执行清单）后再 `task.py start`。
- 本地构建后端：本机已装 JDK17+Maven（见记忆 local-build-toolchain），改完本地编译验证，**不要在生产服务器跑 mvn**（见记忆 prod-build-avoid-server）。
