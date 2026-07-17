# Design — SINGLE 模式活动级成功防重

## 边界

只改 `AutoGrabServiceImpl.tryCreateFromMonitor` 的 SINGLE 分支入口，加活动级成功预检。新增一个 private 查询方法。不动其它方法、不动实体、不动 DDL、不动 doGrab、不动 ALL 模式、不动到点回调。

## 现状链路（修复前）

```
tryCreateFromMonitor(config, sameStoreCombos)
  ├─ parseAccountIds / filterValidAccounts   # 账号优先级列表
  ├─ parsePlatformOrder + orderedCombos        # 平台优先级排序后的组合
  ├─ mode=SINGLE?  → grabExecutor.submit(runSingle(...))
  └─ mode=ALL?     → 每账号 submit(runAllForAccount(...))

runSingle → tryComboWithAccounts → 每账号 hasPlaceholder(账号级) → doGrab → 成功 return
                                                       ↑ 账号级，挡不住换号
```

跨命中：第 N+1 次命中重新进 runSingle，对已成功活动的 account=1 走 hasPlaceholder 查不到占位（lastGrabTime 已填）→ 重新 doGrab → 撞 code=11/饭票不足 → 换号 account=2 → 抢成。**缺活动级成功闸**。

## 修复后链路

```
tryCreateFromMonitor(config, sameStoreCombos)
  ├─ parseAccountIds / filterValidAccounts
  ├─ parsePlatformOrder + orderedCombos
  ├─ [NEW] mode=SINGLE 时：filterActivityGrabbed(orderedCombos, userId)
  │        移除当天已抢成功(auto=true,status=DISABLE,lastResult like '成功%')的 promotionId 组合
  │        若移空 → log.info("自动抢单跳过(活动已抢成功)") + return null
  ├─ mode=SINGLE?  → submit(runSingle(...))
  └─ mode=ALL?     → 每账号 submit(runAllForAccount(...))   # 不做活动级预检
```

## 新增方法契约

```java
/**
 * SINGLE 活动级成功防重：返回当天尚未被本配置以任意账号抢成功的 promotionId 集合。
 * 判定：grab_config 当天、auto=true、status=DISABLE、lastResult like '成功%'。
 * 兼容美团(成功 orderId=..)与饿了么/京东(成功)。
 */
private Set<Integer> grabbedSuccessPromotionIds(Integer userId) {
    // SELECT DISTINCT promotion_id
    // WHERE user_id=? AND auto=1 AND status=DISABLE
    //   AND DATE(create_time)=CURDATE() AND last_result LIKE '成功%'
    List<GrabConfigEntity> rows = grabService.lambdaQuery()
            .select(GrabConfigEntity::getPromotionId)
            .eq(GrabConfigEntity::getUserId, userId)
            .eq(GrabConfigEntity::getAuto, true)
            .eq(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.DISABLE)
            .likeRight(GrabConfigEntity::getLastResult, "成功")
            .apply("DATE(create_time) = CURDATE()")
            .list();
    return rows.stream().map(GrabConfigEntity::getPromotionId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
}
```

说明：
- 用 `likeRight("成功")` → SQL `last_result LIKE '成功%'`，兼容两种成功文案。
- `status=DISABLE` 是 doGrab 成功后 GrabServiceImpl:309 置的，成功记录必为 DISABLE；失败记录保持 ENABLE（占位未消费）或被 markConsumed 仅回写 lastGrabTime 不改 status。用 DISABLE 精确锁定成功，避免把"失败但 lastResult 被业务写成含成功字样"的极端记录误判（保守）。
- 不按 promotionId 单查，而是一次拉当天所有成功 promotionId 集合，在 orderedCombos 上做 set contains 过滤，O(n)。

入口过滤：

```java
if (mode == GrabModeEnums.SINGLE) {
    Set<Integer> done = grabbedSuccessPromotionIds(userId);
    if (!done.isEmpty()) {
        orderedCombos = orderedCombos.stream()
                .filter(s -> s.getPromotionId() == null || !done.contains(s.getPromotionId()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (orderedCombos.isEmpty()) {
            log.info("自动抢单跳过(活动已抢成功): userId={}, configId={}", userId, config.getId());
            return null;
        }
    }
    final List<StoreInfo> combos = orderedCombos;
    grabExecutor.submit(() -> runSingle(config, userId, validAccounts, combos, 0, 0));
    return null;
}
```

注意 `orderedCombos` 原本是 `final` 间接被 lambda 捕获；过滤后需重新赋一个 effectively final 引用（上面用 `final List<StoreInfo> combos` 接住）。

## 为什么不放在 runSingle 内部

runSingle 也会被 onScheduledFire 到点回调调用（:319）。若放 runSingle 内，到点回调路径也会过活动级预检——看似更全面，但：
1. 到点回调的 combos 来自 comboSnapshot，且对应的是"等待最高优先级组合到 start"场景；若期间该活动已被另一次命中抢成功，预检拦截是对的。但 R4 决定本次最小改动只堵立即抢入口，定时回调链路若再现再补。
2. 放入口处一次过滤、后续 runSingle/tryComboWithAccounts 零改动，回归面最小。

权衡后选入口过滤。若后续观察到定时回调路径仍有重复抢成功，再下沉到 runSingle 开头（一行调用 `grabbedSuccessPromotionIds` 过滤 combos）。

## 兼容性 / 回滚

- 改动仅 SINGLE 分支 + 1 个 private 方法。ALL 分支、手动抢、定时抢、doGrab 全不动。
- 回滚：删除 `grabbedSuccessPromotionIds` 与入口过滤 5 行即可恢复原行为。
- 无 DDL、无字段、无配置项变化。存量配置零影响。

## 风险

- 误判：若某失败记录的 lastResult 被 markConsumed 写成以"成功"开头（实际不会，markConsumed 写 `失败:code=...`），会被误当成功跳过。用 `status=DISABLE` 双重锁定消除该风险——成功才 DISABLE，失败保持 ENABLE。已覆盖。
- 漏判：doGrab 成功置 DISABLE 与回写 lastResult 同事务（lambdaUpdate 一次），无中间态窗口。不会漏。
