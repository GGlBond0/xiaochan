# PRD: 推送顺序 + markConsumed 可观测修复

## Goal

修复审查 A-4（推送失败后窗口内不重试）与 A-5（markConsumed 静默吞异常致账号对活动永久跳过）。A-7 经动手前核实，**不改**（见下文核实结论）。

## A-4：推送顺序（`BaseTask.runSingle`）

**现状**（`BaseTask.java:118-121`）：
```
savePushedHistory(写推送历史)  →  afterSuccess  →  sendMessage(推送)  →  triggerAutoGrab
```
推送失败时 `sendMessage` 内 `catch` 只 log 不抛，但历史已写 → 下次 cron 去重发现「N 分钟内推过」跳过 → **推送失败的活动窗口内不再重试，用户漏收**。

**改法（方案1，用户确认）**：`savePushedHistory` 移到 `sendMessage` 成功之后。推送成功才写历史（用于下次去重），推送失败不写 → 下次能再推（自然重试）。

- `savePushedHistory` 只服务推送去重；`triggerAutoGrab` 有自己的防重（hasPlaceholder），不依赖 savePushedHistory，顺序无影响。
- `afterSuccess`（STORE_ACTIVITY 已在 B-10 删空实现、super 空）保持原位。
- 风险：推送成功但写历史失败（DB 抖动）→ 可能重复推送。概率低，且重复推一次比漏推好（用户确认接受）。

## A-5：markConsumed 静默吞异常（`AutoGrabServiceImpl`）

**现状**（`AutoGrabServiceImpl.java:434-441`）：
```java
private void markConsumed(Integer configId, int code, String msg) {
    try {
        grabService.lambdaUpdate()...set(lastResult)...set(lastGrabTime)...update();
    } catch (Exception ignore) { /* 尽力而为 */ }   // ← 静默吞
}
```
回写失败时占位未标记 → 下次 `hasPlaceholder` 查到「有未消费占位」跳过 → **该账号对该活动永久跳过**。

**改法**：`catch (Exception ignore)` 改为 `log.warn` 记录失败，使其可观测（回写失败时至少日志可见，便于排查）。最小改动，不改回写机制。

## A-7：不改（核实结论）

动手前核实 `SettingsView.vue` 与 `LotteryServiceImpl`：
- 前端 `:271` 靠 `response.data.success` 判整体，`br.result?.error` 判单条（:415/:450）。
- 现状 `LotteryController` 返回 `ok(vo)`，lotteryInfo 全失败时 vo.error 有值但 `success=true` → 前端 `:271` 进成功分支取 `result=vo`，`br.result.error` 显示失败 → **前端实际能正确显示失败**，仅 HTTP 层 success 语义不纯。
- 日志 `LotteryServiceImpl:104/:157` 已 log.error 失败 → **可观测性已够**。
- 若改成返回 `success=false`，前端 `:274` 走 `result={error: response.data.msg}` 用 msg 而非 vo → **丢失 vo 里 per-task 详细信息**（哪个任务失败、错误码），反而更差。
- **结论：A-7 不改，避免破坏前端详细显示**。审查的 P1 在此场景实为 P3（语义不纯但功能正确）。

## Requirements

- R1 A-4：`BaseTask.runSingle` 中 `savePushedHistory` 调用从 sendMessage 前移到 sendMessage 成功后。`afterSuccess` 保持原位。`triggerAutoGrab` 不动。
- R2 A-5：`AutoGrabServiceImpl.markConsumed` 的 `catch (Exception ignore)` 改为 `catch (Exception e) { log.warn(...) }`，使其可观测。
- R3 不动 A-7、不动 sendMessage 的 catch（推送失败仍 log，但现在因不写历史→下次能重试，无需改 catch）。
- R4 不改业务逻辑、不改前端、不改 DB。

## Acceptance Criteria

- [ ] 后端 `mvn -o compile` BUILD SUCCESS。
- [ ] A-4：`BaseTask.runSingle` 中 savePushedHistory 在 sendMessage 之后调用。
- [ ] A-5：markConsumed catch 内有 log.warn，不再静默。
- [ ] 部署后：推送失败的活动下次 cron 能再推（不再被窗口去重挡）；markConsumed 回写失败时日志可见。

## Out of Scope

- A-7（不改，核实结论见上）。
- 推送失败重试机制（本次靠「下次 cron 自然重推」达成，不另加重试）。
- markConsumed 回写失败时的重试/补偿（仅改可观测，不改机制）。

## Open Questions

无（A-4 方案1 + A-5 可观测已确认；A-7 核实后不改）。
