# PRD: STORE_KEYWORD 分钟级去重与过期清理

## Goal / 用户价值

STORE_KEYWORD（门店关键字）监控当前用「永久去重」——同店一旦推过一次，再也不会通知。用户配置的「斑斓包点」关键词只命中一家店（`喜斓家斑斓包点`），07-17 推过一次后被永久屏蔽，导致监控虽每分钟正常执行、命中门店也被过滤，用户再也收不到通知。

本次将 STORE_KEYWORD 的去重从「永久、按 storeId」改为「按用户全局 N 分钟窗口、按 storeId+promotionId」，并每次执行清理本配置过期历史，使同店在新活动/过期后能再次通知。行为与已落地的 MINIMUM_PAY 保持一致。

## Background / 已确认事实

- MINIMUM_PAY 已在 07-14 任务中完成同类改造并落地，机制可直接复用：
  - N 来源：`user.notify_dedup_minutes`（用户全局值，默认 60，null→60），由 `MinimumPayService.dedupMinutesOf(notifyConfig)` 取（`MinimumPayService.java:40-44`）。
  - 批量去重：`storePushedHistoryService.findPushedWithinMinutes(notifyId, minutes)` 取窗口内已推送记录（`StorePushedHistoryServiceImpl.java:69+`），key=`storeId+promotionId`。
  - 过期清理：`storePushedHistoryService.deleteByNotifyIdOlderThanMinutes(notifyId, minutes)`。
  - 清理钩子：`BaseTask.cleanupExpired(MonitorConfigEntity)` 在 `runSingle` try 块内 `fetchStoreInfos` 之前调用（`BaseTask.java:97`），无命中也清理。`MinimumPayService` 已重写该方法（`MinimumPayService.java:83-93`）。
- STORE_KEYWORD 当前实现（`StoreTask.java`）：
  - `filterStoreInfos` 去重用 `findByNotifyIdAndStoreIdAll`（`StoreTask.java:138-139`）——仅按 `notify_config_id + store_id` 全表查，无时间窗，永久屏蔽。
  - 未重写 `cleanupExpired`，走 `BaseTask` 空实现 → 历史记录永不清理。
  - `StoreKeywordExtNotifyConfig`（`StoreKeywordExtNotifyConfig.java`）字段：keyword / limitDistance / within3km。**无需新增字段**（N 是用户全局值）。
- `StorePushedHistoryService` 已存在的方法可复用，无需新增 service 方法。
- 用户已确认决策：
  - 去重键 = `storeId + promotionId`（与 MINIMUM_PAY 一致；同店新活动不被同店旧活动阻挡）。
  - 同时加过期清理（每次执行清理本配置 N 分钟前记录，与 MINIMUM_PAY 一致）。

## Requirements

- R1 `StoreTask.filterStoreInfos` 的 STORE_KEYWORD 分支去重由 `findByNotifyIdAndStoreIdAll(storeId) == null` 改为按用户全局 N 分钟窗口批量去重，复用 `findPushedWithinMinutes(notifyConfig.getId(), dedupMin)`，key=`storeId+promotionId`，语义与 `MinimumPayService.filterStoreInfos` 对齐。
- R2 `StoreTask` 重写 `cleanupExpired(notifyConfig)`：取用户全局 N（null→60），调 `deleteByNotifyIdOlderThanMinutes(notifyConfig.getId(), dedupMin)`；删除数 >0 时 `log.info`，异常 `log.warn` 不中断主流程。实现参照 `MinimumPayService.cleanupExpired`。
- R3 取 N 的方式与 MINIMUM_PAY 一致：通过 `userService.getById(notifyConfig.getUserId()).getNotifyDedupMinutes()`，null→60。在 `StoreTask` 注入 `UserService` 与 `UserEntity`（若尚未注入）。
- R4 不改 DB schema、不加索引（`notify_config_id`、`create_time` 索引已存在，清理 SQL 走 `notify_config_id` 索引足够）。
- R5 不动 STORE_ACTIVITY 分支、不动前端、不动 `user.notify_dedup_minutes` 的取值/设置接口（用户已能通过 NotifyHistoryView 调 N）。
- R6 保留 `findByNotifyIdAndStoreIdAll` 方法签名（其它分支/调用方可能依赖，07-16 任务也注明保留）。

## Acceptance Criteria

- [ ] 后端 `mvn -o compile` 通过。
- [ ] `StoreTask` STORE_KEYWORD 分支去重改为 N 分钟窗口 + storeId+promotionId 键；`findByNotifyIdAndStoreIdAll` 不再被 StoreTask 调用。
- [ ] `StoreTask` 重写 `cleanupExpired`，每次执行清理本配置过期记录；无命中也清理。
- [ ] 生产部署后实测：configId=5（斑斓包点）在命中且 N 分钟内未推过时能再次收到通知；同店同活动在 N 分钟内不重复推；同店不同 promotionId 视为新活动照常推。
- [ ] 生产日志出现 `configId: 5 清理 N 分钟前的过期推送记录 X 条`（当存在过期记录时）。
- [ ] 不影响 STORE_ACTIVITY 与 MINIMUM_PAY 既有行为。

## Out of Scope

- 前端改动（N 设置入口已存在于 NotifyHistoryView）。
- `user.notify_dedup_minutes` 接口与表结构。
- STORE_ACTIVITY 去重逻辑。
- 新增任何 service 方法或 DB 列/索引。

## Open Questions

无（产品决策已全部确认）。
