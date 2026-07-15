# MINIMUM_PAY 去重键升级为 storeId+promotionId 并批量优化

## Goal

修复 MINIMUM_PAY 监控在跨天/换活动场景下的误挡：当前去重键仅含 `storeId`，导致昨日某活动的推送记录在滑动窗内把今日同店的新活动（新 promotionId）误挡，新活动无法被推送。同时顺手消除去重阶段的 N+1 查询并加覆盖索引。

## Background

- README 记载：同一门店的 `promotion_id` 每天不一样。
- 当前 `MinimumPayService.filterStoreInfos` 调 `findByNotifyIdAndStoreIdWithinMinutes(notifyId, storeId, dedupMin)`，去重键 = `(notifyConfigId, storeId)`，不含 promotionId。
- 典型 case：23:40 推送店A活动 P1（写入 store_pushed_history, storeId=A, promotionId=P1, createTime=23:40）；00:00 cron 触发，先 cleanup 删 `createTime < now-60=23:00` 的记录（23:40 保留），随后该店刷出新活动 P2（promotionId 不同），去重查询 `storeId=A AND createTime>=23:00` 命中 23:40 的 P1 记录 → 误挡 P2，不推送。
- 根因：去重颗粒度是"门店"而非"活动"，跨天新活动被昨日同店记录误关联。

## Scope（本任务只改 MINIMUM_PAY）

In scope:
- MINIMUM_PAY 去重键 `storeId` → `(storeId, promotionId)`。
- 批量查询：去掉 filter 阶段逐店单查的 N+1，改为一次取本配置时间窗内已推送的 `(storeId, promotionId)` 集合，内存比对。
- 加覆盖索引 `(notify_config_id, create_time, store_id, promotion_id)`。
- cleanupExpired 查询随之用同一索引受益（不改其语义）。

Out of scope（明确不动）:
- 时间窗起点模型（滑动窗 `now-N` 原样保留）。
- cron 调度机制、cron 控制去重。
- `user.notifyDedupMinutes` 字段及其在 pageByUser 的展示用途（保留，语义不变）。
- cleanupExpired 的清理策略（仍删 `createTime < now - dedupMin`）。
- STORE_ACTIVITY（当天+命中即停用）、STORE_KEYWORD（按 storeId 永久去重）的现有去重逻辑。

## Requirements

1. MINIMUM_PAY 去重判断以 `(notifyConfigId, storeId, promotionId)` 为键：同一活动在 `dedupMin` 时间窗内已推送过则跳过；不同 promotionId 视为不同活动，不互相阻挡。
2. 去重查询改为批量：一次查询本配置 `createTime >= now - dedupMin` 的已推送记录集合，filter 阶段在内存做差集，消除逐店单查。
3. 数据库新增覆盖索引以支撑批量查询与 cleanup 删除。
4. 不引入新的用户可见配置项；不改变前端字段。
5. 失败空跑（fetch 到列表但无命中、未写 store_pushed_history）不影响去重计时——保持现状语义。

## Acceptance Criteria

- [ ] 23:40 推送店A活动 P1 后，00:00 该店刷出新活动 P2（promotionId ≠ P1）时，P2 被推送，不被 P1 误挡。
- [ ] 同一活动 P1 在 `dedupMin` 窗内重复刷出仍被跳过（去重仍生效）。
- [ ] 一次 MINIMUM_PAY 执行的数据库查询数从 `1 + 候选数 + 1` 降为 `1 + 1 + 1`（拉列表 + 批量去重查询 + cleanup）。
- [ ] 新增覆盖索引被批量去重查询与 cleanup 删除使用（explain 不回表全扫）。
- [ ] STORE_ACTIVITY / STORE_KEYWORD 行为不变。
- [ ] 编译通过（本地 JDK17 + Maven 绝对路径构建），不碰生产服务器 mvn。

## Notes

- 生产服务器内存仅 1.7G，构建在本地或 GitHub Actions，禁止在 121.91.175.192 跑 mvn。
- DDL 上线方式与现有部署拓扑一致，design.md 中明确执行步骤（参考 deploy-topology 记忆，勿臆造）。
- 本任务虽含 DDL，但改动面窄、语义单一，PRD + 简短 design.md 即可，不强制 implement.md。
