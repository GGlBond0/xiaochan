# Design: MonitoryConfig 事务 + afterCommit scheduler

## 方案

加 `@Transactional(rollbackFor = Exception.class)`；scheduler 调用包进 helper，优先 `afterCommit` 推迟，无事务时直接执行。

## helper 设计

```java
/** scheduler 副作用推迟到当前事务提交后执行；无事务上下文则立即执行（防御）。 */
private void afterCommit(Runnable schedulerAction) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { schedulerAction.run(); }
        });
    } else {
        schedulerAction.run();
    }
}
```
需 import `org.springframework.transaction.support.TransactionSynchronizationManager` / `TransactionSynchronization`。

## 各方法改法

1. `addUpdateConfig`：`@Transactional`；`saveOrUpdate(entity);` 后 `afterCommit(() -> monitorCronScheduler.refresh(entity.getId()));`
2. `updateConfig`：`@Transactional`；lambdaUpdate 后 `afterCommit(() -> monitorCronScheduler.refresh(id));`
3. `deleteById`：`@Transactional`；remove 后 `afterCommit(() -> monitorCronScheduler.cancel(configId));`
4. `deleteByLocationId`：`@Transactional`；list 取 ids（事务内读，可见），remove 后 `afterCommit(() -> ids.forEach(id -> monitorCronScheduler.cancel(id)))`。注意：cancel 不读 DB，只取消内存调度，推迟到提交后无副作用。
5. `toggleStatus`：`@Transactional`；lambdaUpdate 后 `afterCommit(() -> monitorCronScheduler.refresh(configId));`

## 为什么 afterCommit 正确

- `MonitorCronScheduler.refresh`：`cancel` + `getById`（读 DB）+ `schedule`。afterCommit 时事务已提交，`getById` 读到新配置。✓
- `MonitorCronScheduler.cancel`：仅 `scheduledFutureMap.remove` + `future.cancel`，不读 DB。推迟到提交后无影响（配置已删，调度本就该取消）。✓
- 事务回滚时：afterCommit 不执行 → scheduler 不被改动（保持旧调度），DB 也回滚 → 一致。✓
- 无事务（不应发生，这些方法都在 controller 线程且加了 @Transactional）：降级直接执行，等价现状行为。✓

## deleteByLocationId 顺序变化

现状：先 cancel（读 list）再 remove。
改后：事务内 list 取 ids + remove，afterCommit 对每个 id cancel。
差异：cancel 推迟到 remove 提交后。cancel 不依赖 DB 状态（只取消内存调度），故无影响。

## 兼容性 / 回滚

- 无 DB schema 变更。
- 回滚：单文件 `git revert` MonitoryConfigServiceImpl.java 恢复无事务行为。
- 现状「无事务自动提交 + 立即 scheduler」改后变「事务 + afterCommit scheduler」——正常路径行为一致（配置最终都改了、scheduler 最终都刷了），异常路径更一致（DB 回滚 scheduler 不动）。

## 风险

| 风险 | 缓解 |
|---|---|
| afterCommit 内 scheduler 抛异常（refresh 失败） | afterCommit 异常不回滚事务（已提交），scheduler 失败仅影响调度刷新，与现状「DB 改了调度未刷新」同类风险，未恶化；可加 try/catch log.warn |
| 无事务上下文误判 | `isSynchronizationActive` 判断，有事务才推迟，无则直接执行 |
| @Transactional 自调用失效 | 本类内无自调用这些方法（都是 controller 调），Spring 代理生效 |
