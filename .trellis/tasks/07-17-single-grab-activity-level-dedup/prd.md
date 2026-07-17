# SINGLE 模式活动级成功防重缺失修复

## 问题（已实测确认）

监控命中自动抢单 SINGLE 模式（抢一个名额）语义被违反：同一次监控配置、同一活动，被两个账号各抢成功一次，得到 2 个名额。

实锤日志（活动 118779162，userId=2，SINGLE 模式，账号优先级 1→2）：

| 时间 | 命中 | 行为 | 结果 |
|---|---|---|---|
| 10:25:14 | 第1次 | account=1 占位(id=26)→doGrab | **成功（名额1）**，lastGrabTime 回写、status=DISABLE |
| 16:30:05 | 第2次 | account=1 占位(id=35)→doGrab | code=11「今日内无法重复参与活动」→降级组合 |
| 19:30:22 | 第3次 | account=1 → code=-1 饭票不足 → **换号 account=2** 占位(id=42)→doGrab | **成功（名额2）** |

## 根因

`AutoGrabServiceImpl.hasPlaceholder`（:379-390）是**账号级**防重，查询带 `loginStateId`：

```
eq(userId).eq(promotionId).eq(loginStateId, account).eq(auto, true)
.and(DATE(create_time)=CURDATE()).isNull(lastGrabTime)
```

它只能挡"同一账号重复抢同一活动"。doGrab 成功后 GrabServiceImpl（:305-310）回写 lastGrabTime 并置 status=DISABLE，于是该成功记录的 lastGrabTime 非空，`hasPlaceholder` 查不到占位 → 不构成对 account=1 的拦截 → 后续命中重新让 account=1 试 → 撞上游 code=11/饭票不足 → 按 R8 换号逻辑 `shouldSwitchAccount` 返回 true → 换到 account=2 抢成。

SINGLE 的语义是"抢一个名额，成功即停"（PRD R6、AC7）。但"成功即停"只在**单次命中的内存循环内**成立（tryComboWithAccounts :178-180 成功 return）。**跨命中**没有任何活动级成功标记阻止换号账号继续抢同一活动。这就是 bug。

ALL 模式不受影响（ALL 本就是每账号各抢一个名额，账号间独立不互挡），不在本次修复范围。

## 修复目标

SINGLE 模式下，一旦某活动被本监控配置以任意账号抢成功，当天后续命中该活动时**整组跳过**，不再进入换号/降级循环，不再用其它账号补抢。

ALL 模式行为不变。

## Requirements

- R1 新增活动级成功判定：SINGLE 模式下，对待抢 promotionId，若当天已存在本监控配置产生的"抢成功"记录（auto=true、status=DISABLE、lastResult 含"成功"、当天），则该活动整组跳过。
- R2 判定在 `tryCreateFromMonitor` SINGLE 分支入口、对 orderedCombos 中的每个 promotionId 做一次预检；命中即从 orderedCombos 移除该组合（该活动不抢）。
- R3 移除后 orderedCombos 为空 → 按现有"无勾选平台命中"路径处理（info 日志 + return null，不报失败通知，因为成功已完成使命）。
- R4 不改 `hasPlaceholder`、不改 `tryComboWithAccounts`、不改 doGrab 契约、不改 ALL 模式、不改到点回调（onScheduledFire/runSingle 续推）。
  - 注：到点回调续推路径理论上同样可能跨命中重复，但其前置是"未到 start 建定时任务"，且定时任务只针对最高优先级组合；若该活动已成功，R2 入口预检会在下次命中时拦截。本次先在立即抢入口做预检，定时回调链路后续若再现再补（保守，最小改动）。
- R5 判定 SQL 仅依赖现有字段（auto、status、lastResult、create_time），不加新字段、不加新 DDL。
- R6 成功判定口径与 GrabServiceImpl 一致：lastResult 在美团成功为 `成功 orderId=...`、饿了么/京东为 `成功`。用 `like '成功%'` 匹配，兼容两端。

## Acceptance Criteria

- [ ] AC1 SINGLE 模式，account=1 抢成功后，后续命中同一 promotionId 不再产生任何新 grab_config 占位（日志无"已建占位"记录对应成功活动）。
- [ ] AC2 SINGLE 模式，account=1 抢成功后，account=2 不会被用来补抢同一活动（不换号补抢）。
- [ ] AC3 ALL 模式行为与修复前完全一致（每账号独立抢，账号间不互挡）。
- [ ] AC4 SINGLE 模式全部候选活动均已被抢成功时，命中只记 info 日志、不报失败通知、不建占位。
- [ ] AC5 成功判定兼容美团（lastResult=`成功 orderId=...`）与饿了么/京东（lastResult=`成功`）。
- [ ] AC6 本地 `mvn compile` 通过；改动不引入新 DDL/新字段。
- [ ] AC7 不破坏手动抢/定时抢（非监控触发）链路（doGrab 契约不变）。

## Notes

- 实锤日志：服务器 `/opt/xiaocan/logs/info.log` 2026-07-17，活动 118779162。
- 复现配置：configId=3，SINGLE，账号优先级 1→2，平台美团。
- 本任务为 bug 修复，涉及单文件 `AutoGrabServiceImpl.java` 运行逻辑。复杂度中等偏轻，按 lightweight 处理：prd + design + implement。
