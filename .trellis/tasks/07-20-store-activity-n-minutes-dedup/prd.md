# PRD: STORE_ACTIVITY 改持续监控+跨天再通知（去掉命中即停）

## Goal

修复审查 B-10：STORE_ACTIVITY（指定门店活动提醒）现状是「命中推送一次后 `toggleStatus(DISABLE)` 永久停用」，导致**第二天该店有新活动（新 promotionId）时监控已停、用户收不到通知**。改为对齐 MINIMUM_PAY 的「持续监控、当天不重复、跨天新活动能再推」语义，实现 README todo「以及再次通知」对 STORE_ACTIVITY 的补齐。

## 背景（审查 + 代码核实结论）

- STORE_ACTIVITY 现有去重：`StoreTask.execute:90` 跑前 `checkRepeat` 查 `store_pushed_history` 里**今天**有无该配置记录，有则整个跳过 runSingle（当天不重复推）。
- 命中后：`StoreTask.afterSuccess:172-174` `if (命中 && STORE_ACTIVITY) toggleStatus(DISABLE)` → 永久停。
- MINIMUM_PAY 不停用，靠 N 分钟窗去重 + cleanupExpired 跨天复活；STORE_ACTIVITY 单店、当天一次性，用 `checkRepeat` 当天去重比 N 分钟窗更合适。
- 用户确认：活动每天更新、隔天 promotionId 变、一家店一天一个名额；预期「当天推一次，第二天有新活动再推」。

## 关键洞察（最小改动依据）

`checkRepeat` 查询条件是 `createTime >= 今天0点`：
- 当天命中推送 → 写历史 → 下次 cron → checkRepeat 查到今天有记录 → 跳过（当天不重复）。
- 第二天 0 点后 → checkRepeat 查「今天」无记录 → 跑 → 命中新 promotionId → 推送。

**删掉 `afterSuccess` 的 toggleStatus(DISABLE) 三行即可达成「再次通知」**：监控持续活着，checkRepeat 天然提供当天去重、跨天自动复活。无需 N 分钟窗、无需改 cleanupExpired、无需加复活机制。

## Requirements

- R1 删 `StoreTask.afterSuccess:172-174` 里 STORE_ACTIVITY 的 `toggleStatus(DISABLE)` 分支（命中不再停用）。
- R2 保留 `checkRepeat` 当天去重（`execute:90` 不动）——它正好等价「当天推过不再推」。
- R3 不动 `cleanupExpired`（STORE_ACTIVITY 走 checkRepeat 当天去重，不需 N 分钟清理；现有 `type != STORE_KEYWORD` 提前 return 保持）。
- R4 不动 STORE_KEYWORD / MINIMUM_PAY 分支、不动前端、不动 DB。
- R5 兼容存量：已处于 DISABLE 状态的旧 STORE_ACTIVITY 配置（历史命中被停用的）不会自动复活——用户需手动重新启用一次。本次只改「今后命中不再停」，不批量复活存量 DISABLE。在交付说明里告知用户。

## 验收标准

- [ ] 后端 `mvn -o compile` BUILD SUCCESS。
- [ ] `StoreTask.afterSuccess` 不再对 STORE_ACTIVITY 调 toggleStatus(DISABLE)；STORE_ACTIVITY 命中后监控保持 ENABLE。
- [ ] checkRepeat 当天去重保留：同店同活动当天命中推送一次后，当天后续 cron 不再重复推送。
- [ ] 跨天验证（部署后生产或手动模拟）：第二天新 promotionId 能被推送到。
- [ ] 不影响 STORE_KEYWORD / MINIMUM_PAY 行为。

## Out of Scope

- 不批量复活存量 DISABLE 的 STORE_ACTIVITY 配置（手动处理）。
- 不改前端。
- 不引入 N 分钟窗到 STORE_ACTIVITY（checkRepeat 当天去重已够）。

## Open Questions

无（改造方案已通过代码核实确认最小可行）。
