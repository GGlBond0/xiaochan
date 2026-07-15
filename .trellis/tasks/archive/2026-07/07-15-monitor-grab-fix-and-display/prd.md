# 监控自动抢单修复与抢单页活动信息展示

## 背景

2026-07-15 上线的 `07-15-monitor-auto-grab`（commit 06944e9）让监控命中后自动建抢单任务。实测发现两类问题：

1. **自动建的任务多数不抢单**：生产日志可见 `自动抢单已建任务: grabConfigId=10, executeAt=2026-07-15T17:00:04.191` 紧跟 `抢单配置 10 的 executeAt 已过期，跳过`。且这些僵尸任务持续触发防重，把后续命中也挡住（日志 18:20、18:30 的 `自动抢单跳过(已存在)`）。
2. **抢单页看不到活动信息**：前端 `GrabConfigView.vue` 抢单列表只有「活动ID」，没有商家名/优惠明细/活动时段，无法直观核对。

## 目标

- 问题1：监控命中后「立即抢」分支由后端直接执行 `doGrab`，不再注册 cron 调度、不再进前端任务列表；同时保留防重与执行历史。定时抢分支保持现状。
- 问题2：抢单页（列表）展示对应活动的商家名、优惠明细、活动时段。

## 需求

### R1 自动抢单链路修复（后端）

- R1.1 监控命中、`autoGrab=true`、美团活动、登录态有效、在活动时段内（立即抢）：后端**直接异步执行** `grabService.doGrab(config, "AUTO")`，**不**注册 `GrabCronScheduler`、**不**进前端抢单列表。
- R1.2 「定时抢」分支（未到点）：仍建 `grab_config` + 注册 cron（保持现状），会进前端列表。
- R1.3 立即抢需落库一条记录作为「防重锁」与「执行历史载体」，但此记录**不展示在抢单配置列表**中。用新增字段 `auto BOOLEAN`（自动抢单产生）标记，前端列表查询过滤 `auto != 1`。
- R1.4 防重语义修正：同 userId + promotionId + 当天已有「执行中/已抢到」的自动抢单记录则跳过；仅「ENABLE 占位但已过期未执行」的僵尸不再永久挡新命中。
- R1.5 立即抢直接执行不得阻塞 `BaseTask.runSingle` 的 monitor-cron 线程（异步/独立线程池）。
- R1.6 立即抢失败/成功仍按现有 `doGrab` 逻辑写 `grab_history`、推送结果。

### R2 抢单页活动信息展示（前端+后端）

- R2.1 `grab_config` 新增快照字段：`store_name`（商家名）、`promo_detail`（优惠明细，如「满20返15」）、`start_time`/`end_time`（活动时段 HH:MM）。
- R2.2 定时抢单建任务时，从监控命中的 `StoreInfo` 冗余写入上述快照字段。
- R2.3 `GrabConfigVO` 增加对应字段返回；`GrabServiceImpl.listByUserId` 的映射透传。
- R2.4 前端 `GrabConfigView.vue` 列表新增列：商家名、优惠明细、活动时段；手动新建/编辑的配置无快照时显示为空（不报错）。
- R2.5 前端「从活动列表选」带入 promotionId 时，把选中的 store 快照也写入表单一并提交（让手动建的定时任务也有展示信息）。

## 验收标准

- [ ] AC1 开启 autoGrab 的监控配置命中时段内活动时，日志出现 `doGrab` 执行记录（`triggerType=AUTO`），`grab_history` 有一条对应记录，**不再**出现「executeAt 已过期，跳过」对应自动抢单的日志。
- [ ] AC2 同一活动当天重复监控命中，不会重复发起 `doGrab`（防重生效），且不再因僵尸任务永久跳过。
- [ ] AC3 立即抢产生的 `grab_config` 记录 `auto=1`，**不出现在**前端抢单配置列表中；定时抢产生的 `auto=0`，正常出现在列表中。
- [ ] AC4 抢单配置列表对自动/手动定时任务显示「商家名」「优惠明细」「活动时段」三列；手动新建并选择活动后保存的任务也有快照信息。
- [ ] AC5 `BaseTask` monitor-cron 线程不因立即抢阻塞（异步执行可验证：监控 cron 调度间隔不被拖长）。
- [ ] AC6 零回归：手动抢单、定时 cron 抢单、监控通知、登录态过期提醒等既有路径不变。

## 约束

- 生产构建本地进行，不在服务器跑 mvn（[[prod-build-avoid-server]]）。
- 部署路径/服务名以 [[deploy-topology]] 为准：后端 JAR `/opt/xiaocan/xiaocan.jar`，systemd `xiaocan.service`；前端 `dist` 部署 `/var/www/xiaocan/dist`。
- DDL 走 `ddl.sql` 追加，生产手动执行（附在实现说明里）。
- 立即抢异步执行需考虑 `doGrab` 已有的线程安全（`XiaochanHttp` 每次 `new`、无共享可变状态）。

## 范围外

- 不改监控配置页（MonitorConfigView）已上线的 autoGrab 开关/账号下拉。
- 不重构 `GrabCronScheduler` 的定时分支逻辑。
- 抢单历史页（GrabHistoryView）已有 storeName/promoDetail 展示，本次不改。
