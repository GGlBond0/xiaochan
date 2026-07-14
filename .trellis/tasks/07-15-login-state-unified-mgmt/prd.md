# 登录态统一管理页与选择框引用

## 背景 / 问题

项目里"小蚕 App 账号登录态"（一组抓包 HTTP header：X-Sivir JWT / X-Session-Id / X-Vayne / X-Teemo / X-Nami 等）被历史上拆成两张表、两套字段名、两套录入逻辑：

- `grab_login_state`（抢单用，字段前缀 `xc_`，绑 `location_id`，有 `expire_at`）
- `lottery_auth`（霸王餐用，无前缀，有 `city_code` / `raw_headers`）

二者存的是同一种东西。`LotteryAuth.java` 注释自己也写"同为 Android 登录态来源，方便项目其它部分复用"，但实际未复用，反而复制了一套。这是项目混乱的真正根源。

> 注：本项目另有两种不相关 token，不在本次统一范围：`user.token`（前端访问后端的系统鉴权）、`user.spt` / `location_push_target.spt`（WxPusher 推送 token）。

## 目标

把抢单与霸王餐的"小蚕 App 账号登录态"合并为**单一登录态池**，提供**统一管理页**，各业务模块（抢单配置、霸王餐刷任务、卡券查询）都从这一池用**选择框**引用。

## 范围

- 后端：新建 `login_state` 单表；迁移 `grab_login_state` + `lottery_auth` 数据；提供统一 CRUD + 列表接口；抢单 / 霸王餐 / 卡券查询改读新表。
- 前端：新增 `/login-state` 统一管理页（列表 + 录入 + 编辑 + 删除 + 过期状态）；抢单配置、霸王餐刷任务、卡券查询改用统一选择框引用；`SettingsView` 的霸王餐登录态录入段下线，刷任务改为选已有登录态。
- 地址绑定：保留为可选字段（`location_id` 可空），抢单配置下拉可按地址筛选可选登录态；不绑地址的登录态（如霸王餐）该字段为空。

## 不做

- 不动 `user.token`、`user.spt`、`location_push_target.spt`（推送/系统鉴权）。
- 不改抓包 header 解析正则逻辑（仅迁移到统一 service，解析规则保持兼容纯文本与抓包 JSON 两种粘贴格式）。
- 不改代理配置、商家黑名单等无关模块。

## 约束

- 数据迁移必须可回滚（保留旧表与旧代码路径，灰度后再删）。
- 不可造成已录入登录态丢失：迁移脚本需保留 `raw_headers` 留底字段。
- 字段统一：合并后字段用一套命名（去掉 `xc_` 前缀），统一为 `sivir / session_id / user_id(=vayne) / silk_id / nami / city_code / expire_at / location_id / raw_headers`。
- `sivir` 列长度按两张表较大者（`grab_login_state.xc_sivir` VARCHAR(800)）取 800，避免截断。

## 验收标准

- [ ] 后端 `login_state` 单表建好，`grab_login_state` 与 `lottery_auth` 数据完整迁入（条数对得上，字段映射正确，`raw_headers` 不丢）。
- [ ] 后端统一接口：`POST/GET/DELETE /api/login-state`（+ 列表 `/api/login-state/list`），录入支持纯文本与抓包 JSON 两种粘贴格式，解析 JWT exp 写 `expire_at`。
- [ ] 抢单配置（`grab_config.login_state_id`）引用新表；抢单执行、卡券查询（`listCards` / `countCards`）按 `loginStateId` 查新表，行为与迁移前一致。
- [ ] 霸王餐刷任务（`runTask(authId)`）改读新表，`city_code` 等字段可用；`SettingsView` 录入段下线，改为选已有登录态。
- [ ] 前端 `/login-state` 管理页：列表（别名 / 账号 / 过期状态 / 绑定地址）、录入/编辑弹窗、删除；过期状态正确显示。
- [ ] 前端抢单配置、霸王餐刷任务、卡券查询三处下拉用同一登录态列表接口。
- [ ] JWT 过期提醒定时任务（`GrabJwtExpireTask`）改扫新表。
- [ ] 迁移可回滚：旧表/旧接口保留至验证通过后再删（分阶段，删除单列一次提交）。
- [ ] 验证：本地构建后端通过；前端打包通过；迁移脚本在测试库跑通且条数核对。

## Notes

- 抢单侧已部分成型（`LocationView` 管理 + `GrabConfigView` 下拉引用），本次主要把它从"按地址嵌在地址卡里"提升为独立统一页，并把霸王餐侧收拢进来。
- 选择框显示：`name (账号 xcUserId / 已过期|即将过期|正常)`。
