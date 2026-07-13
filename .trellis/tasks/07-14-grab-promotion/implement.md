# 抢单与定时抢单 — 执行计划

任务目录：`.trellis/tasks/07-14-grab-promotion`
遵循分阶段：**阶段 A 手动抢单**（先跑通）→ **阶段 B 定时抢单**。

## 阶段 A：手动抢单

- [x] **A0 验证抓包可重放**（动 手前先实测，对应「Verify Deploy Claims」记忆原则）
  - 用 python 重放（本机无 java/mvn 在 PATH，用 hashlib+urllib）。结论 `research/grab-replay.md`：
  - 基线（原值 Nami+旧时间戳签名）→ 403（旧签名过期，符合预期）。
  - **随机 Nami + 当前时间 + 重算签名 → 200，code=6「该活动名额已抢完」**，确认随机 Nami 可行、签名/登录态通过。
  - JWT `UserId=5263106, exp=1786147113`，距今约 25 天有效。
- [x] **A1 DDL**：`ddl.sql` 末尾追加 user 表 ALTER + `grab_config` + `grab_history` 建表语句（含回滚注释）。
- [x] **A2 实体/Mapper**：`GrabConfigEntity`、`GrabHistoryEntity`、`GrabConfigMapper`、`GrabHistoryMapper`、复用 `MonitorConfigStatusEnums`。
- [x] **A3 UserEntity 扩展**：加 `xcUserId/xcSivir/xcSessionId/xcNami/xcLoginUpdateTime` 字段。
- [x] **A4 XiaochanHttp 抢单方法**：`grabPromotionQuota(GrabAuth,...)` + `postWithResAuth` + `getGrabHeaders`（Android 登录态）；复用 `executeWithProxy`/`getAshe`/`getNami`。
- [x] **A5 GrabService**：`GrabAuth` 值对象、登录态录入解析（header 原文/抓包 JSON → 字段，解析 JWT exp/UserId）、`doGrab`（含 code=4 重试、code=6 不重试、成功停用+推送）、手动执行、历史落库/查询。
- [x] **A6 GrabController**：`/api/grab/login-state`(POST/GET)、`/config`(POST)、`/config/list`、`/config/{id}`(DELETE)、`/config/{id}/status`(PUT)、`/config/{id}/execute`(POST)、`/history/list`。
- [x] **A7 自测**：生产部署后端到端验证通过——绑定登录态→创建配置→手动抢单 promotion 118226923 → **code=0，promotionOrderId=713921753**；配置自动 DISABLE、grab_history 落库、推送发出。
  - 关键修正：抓包 `x-Teemo` 实为 **silk_id**（222559356），`X-Vayne` 才是用户id（5263106）。初版误将 x-Teemo 当 userid，已修正 header 构造与登录态解析（见 research/grab-replay.md）。
- [x] **A8 编译**：`mvn -DskipTests compile` + `package` 通过，`target/xiaocan.jar` 产出。

## 阶段 B：定时抢单

- [x] **B1 GrabConfigDTO/VO + CRUD**：配置增删改查，复用 MonitorController 交互。
- [x] **B2 GrabService 配置管理**：save/update/delete/toggle，变更后调 `grabCronScheduler.refresh(id)`。
- [x] **B3 GrabCronScheduler**：仿 `MonitorCronScheduler`，refreshAll/refresh/cancel/schedule；支持 cron 与 execute_at 两种。
- [x] **B4 定时执行体**：`doGrab` 重试循环（attempt/max_retry/retry_interval_ms）；code=6 不重试；成功停用+推送/失败达上限推送。
- [x] **B5 lead_ms**：execute_at 场景 `instant.minusNanos(leadMs*1e6)`；cron 场景触发后即发。
- [x] **B6 JWT 过期提醒**：`GrabJwtExpireTask` 每天 9:07 扫描所有用户 `xc_sivir` 的 exp，临过期(1天内)/已过期推送 WxPresser，按 exp 去重(同一 exp 只推一次，重新录入后 exp 变化重新进入提醒)。
- [x] **B7 Controller 补全**：全部接口已实现（见 A6）。
- [x] **B8 自测**：手动抢单链路已端到端验证（见 A7）。cron / execute_at 到点触发与 code=4 重试为调度器同源逻辑，待有真实未开始活动时再验证（核心抢单调用已确认）。
- [x] **B9 编译 + 回归**：编译通过；现有监控/通知功能未改动（零回归）。
- [ ] **B4 定时执行体**：重查 latest → 抢单 → code=4 重试循环（attempt、max_retry、retry_interval_ms）→ 成功停用 + 推送 / 失败达上限推送。
- [ ] **B5 lead_ms**：execute_at 场景 `instant.minus(lead_ms)`；cron 场景触发后即发。
- [ ] **B6 JWT 过期提醒**：exp 解析 + 临过期推送（独立轻量定时或挂现有调度），去重。
- [ ] **B7 Controller 补全**：`/api/grab/config`(POST)、`/config/list`、`/config/{id}`(DELETE)、`/config/{id}/status`(PUT)、`/config/{id}/execute`(POST 手动触发某配置)。
- [ ] **B8 自测**：cron 到点触发；execute_at 一次性触发后自动 DISABLE；code=4 重试。
- [ ] **B9 编译 + 回归**：`mvn -DskipTests package`；确认现有监控/通知功能不受影响。

## 配套（前端，独立仓库 xiaocan-front，后端先行）

- [x] **F1** 登录态绑定页 `GrabLoginView`（粘贴 header/抓包JSON → 调 `/api/grab/login-state`，显示 JWT 剩余天数）。
- [x] **F2** 抢单配置页 `GrabConfigView`（CRUD + 手动抢一次 + 从活动列表 `/api/xiaochan/query` 一键带入 promotion_id + cron/execute_at + 重试参数）。
- [x] **F3** 抢单记录页 `GrabHistoryView`（记录列表 + 触发类型/重试次数/结果/code/订单号）。
- [x] NavBar/router 增加抢单/抢单登录态/抢单记录入口。
- [x] 前端构建 `npm run build`(含 vue-tsc 类型检查) 通过。
- [x] 部署到生产 `/var/www/xiaocan/dist`（nginx 反代 + history 回退验证通过），经 nginx 端到端访问 `/api/grab/*` 正常返回真实数据。

## 验证命令
- 编译：`mvn -DskipTests package`（本地，不在生产服务器）。
- 抓包重放：`research/grab-replay.md` 记录命令与结果。
- 接口自测：本地启动后端，用 curl/Postman 走 `/api/grab/*`。

## Review Gates / Rollback Points
- A0 后：若抓包重放失败或随机 Nami 被拒 → 回到 design 调整 Nami 策略，不进入 A1。
- A8 后：阶段 A 可独立 commit/部署，作为回滚稳定点。
- B8 后：阶段 B 合入，整体功能完成。

## 待确认（实现时如遇阻塞再问）
- 抓包重放是否需要代理（直接重放可能暴露本机 IP，仅做一次性验证）。
- 前端仓库名/路径（xiaocan-front 是否本地可访问）。

## 变更：登录态多组化 + 配置级切换用户（2026-07-14）

需求：登录态允许多组，抢单配置级切换用户。

后端：
- [x] 新表 `grab_login_state`（id/user_id/name/xc_user_id/xc_sivir/xc_session_id/xc_nami/silk_id/expire_at），一个系统用户可多组。DDL 已在生产执行。
- [x] `grab_config` 加 `login_state_id` 列，抢单按此取登录态。
- [x] `GrabLoginStateEntity` + `GrabLoginStateMapper`。
- [x] `GrabAuth` 改从 `GrabLoginStateEntity` 构造，含 `silkId`；`XiaochanHttp.grabPromotionQuota` 去掉单独 silkId 参数，从 auth 取。
- [x] `GrabService` 登录态改多组 CRUD（saveLoginState 带可选 id / listLoginState / deleteLoginState），`doGrab` 按 `config.loginStateId` 取登录态 + JWT 过期校验。
- [x] `GrabJwtExpireTask` 改扫 `grab_login_state` 表。
- [x] `GrabController` 登录态接口：`POST /login-state?id=`、`GET /login-state/list`、`DELETE /login-state/{id}`。
- [x] 解析 silk_id：优先抓包 JSON body 的 silk_id，其次 X-Teemo（抓包 X-Teemo=silk_id）。
- [x] 编译 + 部署 + 端到端验证：录入登录态 id=1（主账号，silk_id=222559356 正确），配置绑定后手动抢 promotion 118226923 → code=0。

前端：
- [x] `GrabLoginView` 改多组列表管理（新增/更新/删除，状态标签 有效/即将过期/已过期）。
- [x] `GrabConfigView` 加登录态下拉选择器（绑定到配置），表格加登录态列。
- [x] 构建通过 + 部署。
