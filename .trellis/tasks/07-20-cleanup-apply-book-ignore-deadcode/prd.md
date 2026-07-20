# PRD: 清理 apply/book/ignore 死代码

## Goal

清理审查 A-6 发现的死代码：后端 `XiaoChanController` 的 `apply/book/ignore` 三个空实现接口、配套 VO 类、前端 `handleApply/handleIgnore` 两个无按钮调用的函数。这些是早期「手动报名」形态遗留，后端空转、前端无入口（模板未渲染），不影响在用功能，纯属脏代码。

## 背景（审查结论）

- 后端 `XiaoChanController.java:39-62` 三接口仅 `return BaseResult.ok()`，入参丢弃。
- 前端 `HomeView.vue`：`handleApply`(257)、`handleIgnore`(285) 两函数**模板未调用**（死代码）；`handleBook`(275) 被 `:867`「到货提醒」按钮调用，但 `handleBook` 内是开监控配置弹窗、**不调 `/api/xiaochan/book`**，故后端 `book` 删除不影响它。
- `BookVO` / `IgnoreStoreVO` 仅被 XiaoChanController 引用，删 controller 方法后即无引用。
- 核实确认无后端内部调用、无其它前端调用点。

## Requirements

- R1 删 `XiaoChanController.apply`、`book`、`ignore` 三个方法及其 import（BookVO、IgnoreStoreVO）。
- R2 删 `BookVO.java`、`IgnoreStoreVO.java`（确认删 controller 方法后无其它引用）。
- R3 删前端 `HomeView.vue` 的 `handleApply`、`handleIgnore` 函数（保留 `handleBook`，它在用）。
- R4 不动 `handleBook`、不动「到货提醒」按钮、不动监控配置弹窗逻辑。
- R5 删后编译通过（后端 mvn）、前端 type-check/build 通过。

## Acceptance Criteria

- [ ] 后端 `mvn -o compile` BUILD SUCCESS。
- [ ] 前端 `npm run type-check`（或 build）通过。
- [ ] grep 确认 `apply/book/ignore`（XiaoChanController 三方法）、`handleApply/handleIgnore`、`BookVO/IgnoreStoreVO` 全部消失，无残留引用。
- [ ] `handleBook` 及「到货提醒」按钮保持原样可用。

## Out of Scope

- 不实现报名/忽略功能（已确认废弃）。
- 不动其它 XiaoChanController 方法（`query` 保留）。

## Open Questions

无（死代码边界已核实）。
