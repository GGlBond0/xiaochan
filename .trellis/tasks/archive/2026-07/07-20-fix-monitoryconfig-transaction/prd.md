# PRD: MonitoryConfig 事务补齐 + scheduler 调用推迟到提交后

## Goal

修复审查 A-8：`MonitoryConfigServiceImpl` 的配置写方法（addUpdateConfig/updateConfig/deleteById/deleteByLocationId/toggleStatus）「DB 写 + `monitorCronScheduler` 副作用」两步无事务，存在「DB 改了调度未刷新」「DB 多步非原子」不一致风险。改为事务包 DB 操作 + scheduler 调用推迟到事务提交后（afterCommit），保证 DB 原子性与 scheduler 读到已提交配置。

## 背景（动手前发现的关键坑）

- `MonitorCronScheduler.refresh` 内部 `getById` 读 DB 重新调度。若在事务内（未提交）调用 refresh，读到旧配置 → 调度错乱。**故不能简单加 @Transactional 把 scheduler 留在事务内**。
- 现状无 @Transactional，MP 每条 SQL 自动提交，refresh 能读到刚改配置——现状可跑通，但多步操作（deleteByLocationId 的 list+remove）非原子。
- 方案 A：加 `@Transactional(rollbackFor=Exception.class)` 保证 DB 原子；scheduler 调用用 `TransactionSynchronizationManager.registerSynchronization` 的 `afterCommit` 推迟到事务提交后执行，确保 refresh 读到已提交配置。

## Requirements

- R1 给 5 个方法加 `@Transactional(rollbackFor = Exception.class)`：addUpdateConfig、updateConfig、deleteById、deleteByLocationId、toggleStatus。
- R2 scheduler 调用（refresh/cancel）改为 `afterCommit` 推迟：事务提交后才执行，保证 refresh 读到已提交配置、cancel 在配置已落库后取消调度。
- R3 无事务上下文时（理论上这些方法都在 controller HTTP 线程，必在事务内）兼容降级：`registerSynchronization` 需事务存在，若无事务则直接执行 scheduler 调用（防御，正常不触发）。
- R4 不改方法签名、不改 controller、不改业务校验逻辑。
- R5 不动 MonitorCronScheduler 本身。
- R6 deleteByLocationId 现状「先 cancel 再 remove」改为「remove 在事务内、cancel 在 afterCommit」——cancel 只取消内存调度不读 DB，顺序无影响；remove 提交后 cancel 更安全。

## 严重度

A-8：P1（配置↔调度一致性）。现状风险中等（失败概率低，但失败后用户需手动 toggle 刷新）。

## Acceptance Criteria

- [ ] 后端 `mvn -o compile` BUILD SUCCESS。
- [ ] 5 方法均加 @Transactional；scheduler 调用经 afterCommit 推迟。
- [ ] 无事务上下文时降级直接执行（防御，不影响正常路径）。
- [ ] 部署后实测：新增/修改/删除/切换监控配置，scheduler 调度正确刷新（日志出现 `已注册 cron 调度任务` / `已取消 cron 调度任务`），配置 DB 与调度一致。
- [ ] 不影响 STORE_KEYWORD/MINIMUM_PAY/STORE_ACTIVITY 三类监控的正常调度。

## Out of Scope

- 不改 controller、不改 MonitorCronScheduler。
- 不改 LocationServiceImpl.delete 的调度副作用问题（C-21，另开任务）。
- 不改 ProxyConfig/MerchantBlacklist 的 updateConfig 事务（C-19，另开任务，它们结构不同：updateById + invalidate 缓存，无读 DB 的 scheduler）。

## Open Questions

无（方案 A 已确认）。
