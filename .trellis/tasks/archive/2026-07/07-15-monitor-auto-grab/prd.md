# 监控配置自动建立抢单任务

## Goal

监控配置命中门店活动后，在原有"推送通知"之外，**自动为美团(type=1)活动建立抢单任务并注册调度**，实现"监控看到好活动 → 自动抢"，省去人工去抢单页填 promotionId 建任务的环节。

未开启自动抢单的监控配置保持纯通知，行为零回归。

## Background（已验证事实）

- 监控命中数据载体 `StoreInfo` 含 `promotionId`(Integer)、`startTime`/`endTime`("HH:MM" 当天时段)、`type`(1=美团/2=饿了么/3=京东)、`storeId`。
- `StoreInfo.promotionId` 与抢单接口 `grabPromotionQuota` 用的上游 `promotion_id` 是**同一字段**，可直接复用，无需二次查询。
- 抢单任务 `grab_config` 目前仅由前端手工 `POST /api/grab` 创建，无任何自动建任务逻辑。
- 监控配置已绑 `locationId`，抢单配置同样用 `locationId`，位置链路现成。
- 登录态统一池 `login_state`，`loginStateService.toGrabAuth(id)` 供抢单使用。

## Requirements

### R1 DB schema
- `monitor_config` 新增 `auto_grab`(TINYINT1 默认0) 与 `grab_login_state_id`(INT NULL)。
- `grab_login_state_id` 语义上指向 `login_state.id`（沿用统一登录态池）。

### R2 监控配置接口扩展
- `monitorConfigDTO` / `MonitorConfigEntity` / `NotifyConfigVO` 增加 `autoGrab`、`grabLoginStateId` 字段。
- 保存接口 `POST /api/notify/config` 校验：`autoGrab=true` 时 `grabLoginStateId` 必填且需属于当前用户。
- 编辑回显带新字段。

### R3 自动建任务触发
- 在监控执行体（`StoreTask` 处理 STORE_ACTIVITY/STORE_KEYWORD、`MinimumPayService` 处理 MINIMUM_PAY）推送通知后，对每个命中 `StoreInfo`，当配置 `autoGrab=true` 且 `StoreInfo.type==1`(美团) 时触发自动建抢单。
- 非美团活动(type=2/3)命中**不建抢单**，仅通知（决策 A）。

### R4 建任务逻辑
- 防重：同 `userId + promotionId + 当天 + status=ENABLE` 的 `grab_config` 已存在则跳过，一天一活动只建一次。
- 登录态校验：建任务前校验 `login_state.expireAt` 未过期；过期则跳过建任务 + 推一条提醒。
- 时间判断（当天日期 + startTime/endTime 拼成 LocalDateTime 与 now 比）：
  - `now < start` → 一次性定时任务，`executeAt = 当天 startTime`。
  - `start <= now < end` → 立即一次性任务，`executeAt = now`。
  - `now >= end` → 跳过不建。
- 组装 `GrabConfigEntity` 复用 `addUpdateConfig` 默认值约定（platform=1, ifAdvanceOrder=false, leadMs=0, enableRetry=true, maxRetry=3, retryIntervalMs=500, silkId=0, status=ENABLE），填 promotionId/loginStateId/locationId/executeAt。
- `saveOrUpdate` 后 `grabCronScheduler.refresh(id)` 注册调度。
- 写 `grab_history` 起始记录，来源标注 `AUTO_MONITOR`。

### R5 前端
- 监控配置表单(`MonitorConfigView`)加「自动抢单」开关；勾选后出现「抢单账号」下拉（复用 `/api/login-state` 列表）。
- 保存带上新字段；勾选自动抢单时账号必填。
- 列表卡片回显「自动抢单」标记。

## Out of Scope

- 非美团(type=2/3)平台活动自动抢单（决策 A，后续上游放开再扩）。
- 跨天活动的时间判断（列表接口只返当天活动，业务上 promotion_id 按天变化，不影响）。
- 多账号轮询/负载均衡抢同一活动（本期一活动一任务一账号）。
- 抢单执行结果回流到监控页展示。

## Acceptance Criteria

- [ ] `monitor_config` 含 `auto_grab`、`grab_login_state_id` 两列，DDL 已写。
- [ ] 保存/编辑接口支持 `autoGrab`+`grabLoginStateId`，`autoGrab=true` 时账号必填校验生效。
- [ ] 监控命中美团活动(type=1)且配置开启自动抢单时，生成 `grab_config`（ENABLE）并注册到 `GrabCronScheduler`。
- [ ] 非美团活动命中**不**生成抢单任务。
- [ ] 同活动一天只建一次（重复命中跳过），不产生重复 `grab_config`。
- [ ] 时间判断正确：未到点建定时任务(executeAt=当天startTime)、在时段内立即抢、过期跳过。
- [ ] 登录态过期时跳过建任务并推提醒，不生成过期任务。
- [ ] `autoGrab=false` 的配置命中后只通知，无任何抢单任务产生（回归零影响）。
- [ ] 前端表单可开关自动抢单、选账号、保存、回显；列表卡片显示标记。
- [ ] 本地构建通过（JDK17+Maven 绝对路径，不在服务器跑 mvn）。

## Notes

- `store_platform` 决策：抢单上游 `grabPromotionQuota` 写死 platform=1，故本期只对美团活动自动抢（决策 A）。
- 不新增 `@Scheduled`；复用现有监控触发 + `GrabCronScheduler`。
- 构建与部署遵循 [[prod-build-avoid-server]]、[[local-build-toolchain]]、[[deploy-topology]]、[[verify-deploy-claims]]。
