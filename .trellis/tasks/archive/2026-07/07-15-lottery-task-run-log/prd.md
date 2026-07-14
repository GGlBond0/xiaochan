# 霸王餐刷任务执行日志明细

## Goal

让"霸王餐刷任务"每次执行的结果对用户有实际可读意义：前端能看到全部 5 个浏览/领取类任务各自的最终状态与原因（已完成跳过 / 本次成功 / 本次失败+原因），而不是像现在那样——当全部任务已完成时只显示四个数字（刷前/刷后机会数、当日累计、变化=0），看不出刷任务到底做了什么。

## Background

- 现状：`LotteryServiceImpl.runTask` 遍历 `FLAG_TO_TYPE` 5 个标志位时，对 `lottery_info` 中已为 true 的 flag 直接 `continue` 跳过，既不记录"已完成-跳过"，也不把已完成的任务放进返回 `tasks`。导致全完成时 `tasks` 为空，前端"完成明细"区不渲染，只剩四个统计数字。
- 失败时（如代理池大面积 403/超时）`runTask` 在 `getLotteryProgress` 抛异常被 `catch`，`vo.setError(e.getMessage())` 只透传原始错误串（如"状态码错误:-1"），`tasks` 同样为空。
- 前端 `SettingsView.vue` 已有"完成明细"区（`runResult.tasks` 循环），但只渲染 `desc / ok / msg`，且 `ok` 是布尔，无法表达"跳过"态；机会变化在 null 时已修为显示 —（本会话上一轮已做）。
- 用户已确认：每个任务展示"最终状态 + 原因"即可，**不需要**拆解每个任务经历的代理重试轨迹（哪个代理 IP 403）。这是改动最小的方案。

## Requirements

### 后端
- R1 `LotteryServiceImpl.runTask` 遍历全部 5 个 flag（`if_shared/is_get_meituan_redpack/is_get_eleme_redpack/is_view_welfare_page/is_view_bwc_page`），**不再跳过已完成的**，对每个任务都产生一条记录放进 `tasks`。
- R2 每条任务记录携带一个明确的**状态枚举**，至少覆盖三种：已完成跳过（SKIPPED）、本次尝试成功（OK）、本次尝试失败（FAIL）。
- R3 失败原因（FAIL）：取 `AddLotteryTimes` 响应 `status.code != 0` 时的 `status.msg`；若调用抛异常，取异常消息并做友好化（如代理 403/超时 → "代理不可用/超时"，而非裸 "状态码错误:-1"）。401 业务拒绝（当日次数已满）当前 `LotteryHttp` 已返回 `status.code=401` 的结构化失败，应体现为 FAIL 并带原因。
- R4 已完成跳过（SKIPPED）：原因文案统一为"已完成"。
- R5 顶层 `LotteryTaskResultVO` 的 `error` 字段保留（用于整次执行因 `lotteryInfo`/`getLotteryProgress` 抛异常而中断的情况），但 `tasks` 应尽可能已填充中断前的内容；前端展示时 error 与 tasks 并存。

### 前端
- R6 "完成明细"区始终展示全部 5 行（只要有 `tasks`），按状态着色：跳过=灰、成功=绿、失败=红。
- R7 每行展示：任务描述、状态标签、原因（失败/跳过时显示原因，成功可省略）。
- R8 顶层 `error` 区域沿用本会话上一轮的友好文案映射（`friendlyError`），与完成明细并存。

### 非目标
- N1 不持久化刷任务记录到数据库（用户已明确：仅本次展示）。
- N2 不展示每个任务经历的代理 IP 重试轨迹。
- N3 不改 `LotteryHttp` 的签名/代理重试内部逻辑，仅在其已返回的结构上消费原因。

## Acceptance Criteria

- [ ] AC1 全部任务已完成时点"刷任务"，前端完成明细显示 5 行，每行状态为"已完成"（灰色），而非空白；统计数字照常显示。
- [ ] AC2 有未完成任务且刷成功时，对应行显示"成功"（绿），已完成行显示"已完成"（灰）。
- [ ] AC3 某任务失败时，对应行显示"失败"（红）+ 原因文案（401 → 当日次数已满类；代理 403/超时 → 友好提示），不显示裸 "状态码错误:-1"。
- [ ] AC4 代理池全挂导致 `getLotteryProgress` 中断时，前端既显示顶层友好 error，也显示中断前已填充的任务明细（若 `lotteryInfo` 成功则 tasks 非空）。
- [ ] AC5 后端本地编译通过（`mvn clean package -DskipTests`），部署到生产后接口返回新结构；前端本地 `npm run build` 通过，部署到生产 dist 后页面展示新明细。
- [ ] AC6 不影响抢单模块、登录态管理、代理设置等既有功能。

## Notes

- 后端登录态读 `login_state` 单池（见 `login-state-unified-pool` 记忆），`LotteryServiceImpl` 已用 `loginStateService.getEntity(authId)`，本次不改动登录态获取逻辑。
- 构建链路：后端本地 `mvn` 构建后 scp jar 到 `/opt/xiaocan/`、备份旧 jar、`systemctl restart xiaocan`；前端绝对路径打包 dist → scp → 备份 → 解压 → chown www-data（见 `frontend-deploy-dist-absolute-path`、`deploy-topology` 记忆）。**永不在生产服务器跑 mvn**（见 `prod-build-avoid-server`）。
