# PRD: 代码质量与潜在 Bug 审查（audit-code-quality）

## Goal

对后端 130 个 Java 文件（9432 行）+ 前端 20 个 vue/ts 文件做正确性扫描，产出带 `file:line` 证据的潜在 bug 清单。承接 prod-health 报告的 F-1（configId 3 全天 0 命中）做根因复核。**只读源码不改代码**。

## 背景（来自 prod-health）

- F-1：configId 3（MINIMUM_PAY, minimumPay=3, location=2）258 次 0 命中，阈值低于 id=4 却无命中。需复核 `MinimumPayService.filterStoreInfos` 是否对某 location/阈值存在误过滤，并建议加上游返回量日志。
- F-2：`XiaochanHttp.executeWithProxy` 对 status=-1 的处理（间歇不可达）。

## Requirements

- R1 **并发/线程安全**：`MonitorCronScheduler`/`GrabCronScheduler` 调度、多账号轮询换号、`MerchantBlacklistHolder`/`LoginStateService` 等共享状态、静态集合是否并发安全。
- R2 **事务/一致性**：service 写操作 `@Transactional(rollbackFor=Exception.class)` 覆盖；`updateById` 忽略 null 坑；批量写事务边界。
- R3 **异常吞掉/边界**：`try{}catch(Exception){}` 空 catch 或只 log 不处理；NPE（未 null 检查的链式调用）；空集合/空数组；HTTP 调用失败后状态是否正确；null 兜底是否到位。
- R4 **去重防重一致性**：STORE_ACTIVITY 当天去重 / STORE_KEYWORD N 分钟窗 / MINIMUM_PAY N 分钟窗 / SINGLE 模式活动级成功防重 / 抢单防重，跨模块语义是否一致、键是否正确、有无回退到旧永久去重。
- R5 **资源/性能**：循环内查库（N+1）；`for` 内调 service；大集合内存（-Xmx256m 约束）；流式处理是否提前取集合避免每元素查库（对照 MinimumPayService 写法）。
- R6 **F-1 根因复核**：读 `MinimumPayService.filterStoreInfos` 全文 + `fetchStoreInfos` + 上游 `XiaoChanService.getList`，判断 configId 3 0 命中是代码问题还是数据问题；给出最小诊断手段（如加返回量日志）。
- R7 **前端**：vue3 组件中 `watch`/`computed` 依赖失配、未 await 的 async、`ref` vs `reactive` 误用、API 错误处理、路由守卫 token 丢失。

## 严重度

- P0 主动 bug（数据错/功能坏/会崩）
- P1 潜在 bug（边界/并发隐患，特定条件下触发）
- P2 代码质量/可维护性（吞异常、N+1、坏味道）
- P3 信息项

## Acceptance Criteria

- [ ] `audit-code-quality.md` 产出，R1–R7 各小节有 `file:line` 证据。
- [ ] 每条问题：位置 + 严重度 + 触发条件 + 是否需修 + 建议归属。
- [ ] F-1 给出明确根因判断（代码 vs 数据）+ 最小诊断建议。
- [ ] 列出「确认正常」的模块/文件。
- [ ] 全程未改源码（`git status` 仅多本 md）。

## Out of Scope

- 修复（另开任务）。性能压测。安全渗透。

## Open Questions

无。
