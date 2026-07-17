# Implement — SINGLE 模式活动级成功防重

文件：`src/main/java/io/github/xiaocan/service/impl/AutoGrabServiceImpl.java`

## 步骤

1. **import 补充**：`java.util.Set`、`java.util.HashSet`、`java.util.Objects`、`java.util.stream.Collectors`（缺哪个补哪个，先看现有 import）。
2. **新增 private 方法 `grabbedSuccessPromotionIds(Integer userId)`**（放辅助区，hasPlaceholder 附近）：
   - lambdaQuery select promotionId，user_id=?、auto=true、status=DISABLE、last_result likeRight '成功'、DATE(create_time)=CURDATE()。
   - 返回 Set<Integer>（filter null）。
3. **改 `tryCreateFromMonitor` SINGLE 分支**（:104-118 附近）：
   - 在 `GrabModeEnums mode = ...` 判定后、`if (mode == ALL)` 之前，插入 SINGLE 预检：
     - `Set<Integer> done = grabbedSuccessPromotionIds(userId);`
     - 若 done 非空：`orderedCombos = orderedCombos.stream().filter(s -> s.getPromotionId()==null || !done.contains(s.getPromotionId())).collect(toCollection(ArrayList::new));`
     - 若 orderedCombos.isEmpty()：log.info "自动抢单跳过(活动已抢成功)" + return null
   - 现有 `final List<StoreInfo> combos = orderedCombos;` 保持（过滤后 orderedCombos 仍是 effectively final 可被捕获；为安全用一个新 final 变量接住过滤结果再传给 runSingle）。
   - 注意：orderedCombos 当前是 `List<StoreInfo> orderedCombos = new ArrayList<>();`（非 final），后续被 lambda 捕获的是 `final List<StoreInfo> combos`。过滤后需确保传给 runSingle 的是过滤后的 final 引用。
4. **不动**：hasPlaceholder、tryComboWithAccounts、runSingle、runAllForAccount、onScheduledFire、ALL 分支、doGrab、buildEntity、DDL、实体。

## 验证

- 本地编译（用绝对路径 JDK17/Maven，见 [[local-build-toolchain]]）：
  ```
  & "C:\<maven>/bin/mvn.cmd" -q -DskipTests compile
  ```
  （先 recall 确认 mvn 绝对路径）
- 编译通过即满足 AC6/AC7。
- AC1-AC5 为运行行为，需部署后观察日志验证（本次先本地编译通过，部署由你触发）。

## Review gate

- 改动 diff 只含 AutoGrabServiceImpl.java，且仅 SINGLE 分支 + 新增 1 方法。
- 确认未误删现有 import、未改 ALL 分支。

## 回滚点

- 单文件 5 行 + 1 方法，`git checkout -- src/main/java/io/github/xiaocan/service/impl/AutoGrabServiceImpl.java` 即恢复。
