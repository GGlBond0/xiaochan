# 小蚕抢单与定时抢单

## Goal

在现有小蚕项目（Spring Boot 后端 + vue3 前端 xiaocan-front）中新增「抢单」与「定时抢单」能力。用户可对指定 `promotion_id` 手动发起抢单，或配置 cron / 精确执行时间由后端自动到点抢，复用现有代理机制规避 IP 封禁，并在抢到或失败时通过 WxPusher 推送结果。

README todo 中「自动、手动抢购活动」即为本任务，当前标记未完成。

## Background

- 抢单接口 `https://gw.xiaocantech.com/rpc`，`servername=Silkworm`，`methodname=SilkwormService.GrabPromotionQuota`，`X-Platform=Android`，与现有活动列表接口（`SilkwormRec` / `mini`）分属不同服务。
- 用户已实测：重放抓包请求（`C:\D\ShareFile\favorites1.json`）可成功抢单，返回 `promotion_order_id`。
- `X-Ashe` 加密逻辑与现有 `XiaochanHttp.getAshe()` 完全一致，可直接复用。
- 抢单接口需「登录态」一组 header（`X-Sivir` JWT / `X-Teemo` / `X-Vayne` / `X-Session-Id` / `X-Nami` / `X-Garen`），现有 `user` 表仅存 `token`、`spt`，需扩展存储登录态。
- README 明确：X-Nami「固定或随机生成均可」——沿用现有 `getNami()` 随机生成即可，无需逆向。
- README 风险提示：调用频率过高会被腾讯云 WAF 拦截封禁数小时；`promotion_id` 同一门店每天不同。

## Requirements

### 功能需求

- **F1 登录态绑定**：用户在前端粘贴整段抓包 header（或抓包 JSON），后端解析提取 `X-Sivir / X-Teemo / X-Vayne / X-Session-Id` 及小蚕 user_id，存入 user 表对应字段。支持更新覆盖。
- **F2 手动抢单**：选择一个 `promotion_id`（可从已有活动列表 / 推送历史一键带入，避免手填错）+ 位置（经纬度 / city_code / silk_id），手动触发一次抢单，返回结果。
- **F3 定时抢单**：配置抢单规则，支持两种触发方式（可共存于不同配置）：
  - cron 表达式（6 位含秒，复用现有 `monitor_config.cron` 约定）。
  - 一次性精确执行时间 `execute_at`（精确到秒），命中后抢一次并自动停用。
- **F4 触发参数可配**：每条配置支持 `lead_ms`（提前量毫秒）、`max_retry`、`retry_interval_ms`、retry 开关。命中后若返回 code=4（活动未开始），按间隔重试至成功或达上限；成功即停用配置。
- **F5 抢单记录**：每次抢单（手动 / 定时 / 每次重试）落 `grab_history`，含返回 code/msg、`promotion_order_id`、attempt、耗时。
- **F6 结果推送**：抢到（code=0，拿到 `promotion_order_id`）或最终失败时，通过现有 `MessageHttp.sendMessage` 推送 WxPusher（复用 `user.spt`）。
- **F7 启停与列表**：抢单配置支持启用 / 停用 / 删除 / 列表查询，与现有 `MonitorController` 一致的交互模式。
- **F8 JWT 过期提醒**：解析 `X-Sivir` 的 `exp`，临过期（如提前 1 天）推送提醒用户重新录入登录态。

### 非功能需求 / 约束

- **N1**：必须复用现有 `ProxyHolder` 代理机制请求小蚕网关，避免直接高频请求导致封禁。
- **N2**：后端构建/部署不得在生产服务器执行 mvn（见部署规范），本地构建后发布。
- **N3**：定时精度受 JVM 调度与网络影响；clock skew 需通过网关 `Date` 响应头校准（best-effort），但不承诺毫秒级绝对精度。
- **N4**：同一 promotion_id 当天有效；配置中 promotion_id 不可跨天复用，需提示用户每日重新选择或从当日活动带入。
- **N5**：前端改动落在独立仓库 xiaocan-front，本任务后端先行，前端为配套阶段。

## Acceptance Criteria

- [ ] **AC1**：前端粘贴一段真实抓包 header 后，后端能解析并存入登录态字段；再次查询可读回。
- [ ] **AC2**：手动抢单接口对一个有效 promotion_id 发起请求，返回小蚕原始 `status.code`；code=0 时落 `grab_history` 并返回 `promotion_order_id`。
- [ ] **AC3**：配置 cron 定时抢单，到点后服务自动触发一次；配置 `execute_at` 一次性任务，到点触发后配置自动转为停用。
- [ ] **AC4**：code=4（活动未开始）场景下，按 `max_retry` / `retry_interval_ms` 自动重试；成功后停用配置并停止重试。
- [ ] **AC5**：抢单成功 / 最终失败均触发 WxPusher 推送，spt 取自 user 表。
- [ ] **AC6**：所有上游请求经 `ProxyHolder` 代理（启用代理时），遇 403 换代理重试，复用现有逻辑。
- [ ] **AC7**：`grab_history` 记录每次尝试（含重试），字段完整可查询。
- [ ] **AC8**：JWT `exp` 临过期前触发推送提醒。
- [ ] **AC9**：DDL 变更以 `ddl.sql` 增量语句形式追加（仿现有 cron 变更写法），不破坏既有表结构。
- [ ] **AC10**：本地编译通过（`mvn -DskipTests package`），现有功能无回归。

## Out of Scope

- 自动从活动列表「智能选择」要抢的 promotion_id（本期仅支持用户显式指定 / 一键带入）。
- 多账号批量抢单（本期单用户单登录态）。
- 抢到后自动下单/支付流程（只到「抢到名额」为止，与抓包一致）。
- X-Nami 的精确逆向算法（README 已说明可随机，沿用 `getNami()`）。

## Notes

- 抓包样本：`C:\D\ShareFile\favorites1.json`（3 条记录，含 1 次成功 code=0、2 次 code=4）。
- 关键 header 含义见 design.md。
- 前端为独立仓库 xiaocan-front；本任务 PRD/design/implement 聚焦后端，前端列为配套工作。
