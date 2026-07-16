# 地址管理登录态改为选择已有登录态

## 背景

项目已有统一登录态池 `login_state`（见 [[login-state-unified-pool]]），并有专门的 `/login-state` 管理页录入抓包 header。
但地址管理页 `LocationView.vue`（路由 `/location`）里"绑定登录态"目前仍是**在地址卡片下粘贴抓包 header 新建登录态**的旧形态——与专门的登录态录入页职责重复，且同一小蚕账号会被后端自动覆盖。

需求：地址管理里改为**选择已有的登录态**进行绑定/解绑，不再在地址页录入登录态明文。

## 现状（实测）

- 后端 `location` 表只存地理信息，**无登录态字段**。登录态在 `login_state` 表，引用方向是 `login_state.location_id → location.id`（一条登录态可选绑定到一个地址）。
- 后端现有 `POST /api/login-state`（save）：`rawHeaders` 是 `@NotBlank`，更新时**必须重传整段 header**；且会整体覆盖 sivir/sessionId/rawHeaders 等全部字段；`locationId` 传 null 不会解绑（`if (locationId != null)` 才 set）。**无法用于"只改 locationId"**。
- `LoginStateVO` 不返回 rawHeaders（安全：不回传 X-Sivir JWT）。前端手上没有原 header。
- 前端地址页登录态相关代码：`LocationView.vue:221-300`（逻辑）、`:470-493`（模板）、`:598-614`（对话框）。下拉数据来自 `/api/login-state/list`（已有 `loadLoginStates`）。
- 可参照的登录态下拉写法：`GrabConfigView.vue:405-411`。

## 目标

1. 地址页"绑定登录态"交互从粘贴 header 改为下拉**选择已有 login_state**。
2. 一个登录态只能绑一个地址：下拉只列出 `locationId` 为空（未绑定）的登录态；已绑定到其它地址的不出现。
3. 支持对已绑该地址的登录态**解绑**（移回未绑定池），不删除登录态本身。
4. 登录态明文的录入只在 `/login-state` 管理页进行，地址页不再出现 rawHeaders 录入。
5. 后端补一个最小接口，只改 `login_state.location_id`，不动登录态明文字段。

## 范围

- 后端：`LoginStateController` + `LoginStateService(Impl)`，新增 `PUT /api/login-state/{id}/location`。
- 前端：`LocationView.vue` 登录态绑定段（对话框 + 列表操作）。
- **不改**：`location` 表、`login_state` 表结构、`/login-state` 管理页、抢单/霸王餐/卡券等其它引用登录态的逻辑。

## 验收标准

- [ ] 地址页"+ 绑定登录态"打开的是下拉选择框，可选项只含未绑定到其它地址的登录态；无可选时提示去「登录态管理」页录入。
- [ ] 选中并保存后，该登录态出现在该地址的登录态列表中，且不再出现在其它地址的下拉里。
- [ ] 已绑该地址的登录态行提供"解绑"操作，解绑后回到未绑定池、可被其它地址选择。
- [ ] 解绑/绑定只改 `login_state.location_id`，登录态明文（sivir/sessionId/rawHeaders 等）不变。
- [ ] 后端校验：登录态与地址均归属当前用户；解绑传 null 生效。
- [ ] 后端编译通过；前端 `npm run build` 通过；地址页无 rawHeaders 录入残留。
- [ ] 旧的"在地址页粘贴 header 新建登录态"对话框 UI 移除。

## 非目标

- 不做"一个登录态绑多个地址"（`location_id` 仍单值）。
- 不迁移历史数据（历史登录态的 `location_id` 已有值则继续生效，会从下拉里隐藏）。
- 不改 `/login-state` 管理页。
