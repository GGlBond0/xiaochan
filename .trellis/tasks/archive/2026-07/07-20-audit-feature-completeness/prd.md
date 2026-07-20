# PRD: 功能完整性缺口审查（audit-feature-completeness）

## Goal

对照 README 更新记录 + 各模块声明功能，核每个功能是否真实现完整、有无半成品/空实现/TODO 遗留/前端有入口后端无实现。承接前两个 child 的发现（尤其 code-quality 的 C-13 apply/book/ignore 空实现）。产出功能缺口清单。**只读不改**。

## 背景（来自前两个 child）

- C-13：`XiaoChanController.apply/book/ignore` 三接口空实现，前端 `HomeView.vue` 有调用 → 需确认是历史遗留还是该实现。
- F-1 撤销：configId 3（minimumPay=3）0 命中是阈值语义合理，但需确认用户对 location_id=2 的监控预期。
- code-quality 多处 TODO 占位（`XiaochanHttp.buildOrderExchangeReq` 饿了么/京东字段未验证）→ 功能完整性质疑。

## Requirements

按 README「更新记录」与「todo」逐项核：

- R1 **README todo 项**：「以及再次通知」未勾选 —— 核真实状态（是否实现、有无相关代码）。
- R2 **监控三类闭环**：STORE_ACTIVITY / STORE_KEYWORD / MINIMUM_PAY 的「配置→cron调度→fetchStoreInfos→filterStoreInfos→cleanupExpired→命中→savePushedHistory→sendMessage→triggerAutoGrab」各环节是否完整，有无断链。
- R3 **抢单闭环**：手动(MANUAL)/cron/一次性精确(ONESHOT) 三触发路径；多账号多平台优先级轮询；SINGLE/ALL 模式；换号降级；抢到/失败推送；饭票校验 —— 逐项核代码是否存在且自洽。
- R4 **霸王餐抽奖**：LotteryService 批量多账号、App 登录态契约、运行日志、`runTask` 触发链路是否完整。
- R5 **登录态统一池**：抢单+霸王餐合并、前端选择框、PUT location 绑定、过期提醒 —— 前后端契约是否对齐。
- R6 **代理 IP 池**：配置页/DB 取值/失效换代理/健康度 —— 核是否缺健康度监控（prod-health F-3）。
- R7 **推送按地址路由**：PushService pushToLocation/pushToUser、spt 兜底、多 spt 去重 —— 核闭环。
- R8 **商家黑名单 / 3km 筛选 / 距离排序**：前后端一致性。
- R9 **半成品扫描**：全仓 grep `TODO/FIXME/XXX/未实现/待定/占位`、空方法体（仅 return ok）、被注释掉的功能块、前端有入口后端无对应接口。
- R10 **configId 5 cron 预期**：`0 0-30 0 * * *` 仅 00:00–00:30 跑，核用户是否预期全天（O-2）→ 属产品预期，本 child 列为「需用户确认」项。

## 严重度

- P0 功能完全缺失（声明有但无实现，且用户在用）
- P1 功能半实现/空实现/字段未验证致功能不可用
- P2 功能存在但有缺口/不一致（前后端契约偏差、缺监控）
- P3 遗留/冗余/可优化

## Acceptance Criteria

- [ ] `audit-feature-completeness.md` 产出，R1–R10 各小节有 `file:line` 或 README 引用证据。
- [ ] 每条缺口：位置 + 严重度 + 是「完全缺」还是「半实现」+ 是否需补 + 建议归属。
- [ ] 明确列出「功能完整、确认正常」的模块。
- [ ] 全程未改源码。

## Out of Scope

- 修复（另开任务）。
- 重新逆向验证（X-Ashe 等已落地为既定事实）。

## Open Questions

- R10 configId 5 cron 是否符合用户预期（需用户确认，本 child 列为待确认项）。
