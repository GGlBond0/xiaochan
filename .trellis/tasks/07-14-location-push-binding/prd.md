# 多spt推送-按地址绑定登录态与推送通道

## Goal

把推送目标 spt 从"挂在系统用户(user)上的单值"改成"挂在地址(location)上的多值",并把抢单登录态(grab_login_state)归属到地址。这样同一地址下的多个抢单登录态命中后,统一推送到该地址绑定的多个 spt,解决"一个用户一个 spt、换设备/多通道无处可推"的痛点。

## Background(已确认事实)

当前数据归属链(代码勘察确认):

- `location`(地址,`LocationEntity.java`):有 `userId`,属于某系统用户;一个用户可有多个地址。
- `grab_login_state`(抢单登录态,`GrabLoginStateEntity.java:16`):只挂 `userId`,**没有 `locationId`** —— 当前缺口。
- `grab_config`(抢单配置,`GrabConfigEntity.java`):已有 `locationId`(行32)和 `loginStateId`(行28),说明"地址↔登录态"关系已隐含在抢单配置里,只是没在地址维度显式管理。
- spt:挂在 `user.spt`(`UserEntity`),单值,与地址无关。

推送现状:4 个调用点全部 `UserEntity.getSpt()` 单值取数:
- `BaseTask.java:190` 监控推送(入参已带 `LocationEntity`,能拿 `locationId`)。
- `GrabServiceImpl.java:629 push()` 抢单推送(只传 `user`,但调用链上层有 `grab_config.locationId` 可传)。
- `GrabJwtExpireTask.java:87` JWT 过期提醒(只传 `user`,登录态新增 `locationId` 后可路由)。
- `SptService.java:43` 注册验证码(按入参 spt 推,不在本次改动范围)。

## Confirmed Decisions(用户已拍板)

1. **登录态固定属一个地址**:同一小蚕登录态不跨地址抢单 → 给 `grab_login_state` 加 `location_id` 单值即可,无需分组绑定。推送按 `locationId` 路由。
2. **spt 绑定不要验证码确认**:spt 非敏感凭证,填错只丢推送无安全风险,自测闭环短。新增/编辑 spt 直接保存,提供"测试推送"按钮但不作为保存前置。`SptService` 验证码仅保留注册流程,多 spt 绑定不走验证码。
3. **验证码不迁 Redis**:验证码只在注册用,内存 Map 保留即可(原计划"顺手迁 Redis"取消)。
4. **`user.spt` 保留为兜底**:无地址 spt 配置时回退 `user.spt`,向后兼容老用户。
5. **老 `grab_login_state.location_id` 留空**:不写自动迁移,引导用户在地址管理里重新绑定。

## Requirements

### R1 数据模型
- R1.1 `grab_login_state` 新增 `location_id`(可空),表示该登录态所属地址。
- R1.2 新建 `location_push_target` 表:`id`、`location_id`、`spt`、`remark`、`enabled`(默认1)、`is_default`、`sort`、`create_time`、逻辑删除;一个地址可多行多 spt。
- R1.3 `user.spt` 字段保留,作为无地址 spt 时的兜底推送目标。
- R1.4 提供数据库迁移 SQL(`ddl.sql` 增量)。

### R2 推送收口
- R2.1 新增 `PushService`,`getPushTargets(Long locationId)` 返回"该地址启用的 spt 去重列表 + 无配置时回退 `user.spt`"。
- R2.2 `pushToLocation(Long locationId, String content, String summary)`:遍历目标并发送。
- R2.3 `pushToUser(Long userId, ...)`:无地址语境兜底(如纯用户级提醒)。

### R3 推送调用点改路由
- R3.1 监控 `BaseTask.sendMessage`:改走 `pushToLocation(locationId)`(locationId 现成)。
- R3.2 抢单 `GrabServiceImpl.push`:从调用链把 `grab_config.locationId` 传入,改走 `pushToLocation`。
- R3.3 JWT 过期 `GrabJwtExpireTask.push`:登录态新增 `location_id` 后改走 `pushToLocation`;无 `location_id` 的老记录回退 `pushToUser`。
- R3.4 注册验证码 `SptService`:不动。

### R4 地址维度绑定/管理接口
- R4.1 登录态绑定到地址:`grab_login_state` 的增删改加 `location_id` 维度;老记录留空。
- R4.2 地址推送 spt CRUD:按 `locationId` 新增/编辑/删除/启停 spt,**无验证码**,直存。
- R4.3 测试推送:按 `locationId`(或指定单个 spt)发一条测试消息,不入业务历史。

### R5 前端(仓库 `xiaocan-front-main`,Vue3+Element Plus)
- R5.1 `LocationView.vue` 地址卡片下新增"抢单登录态"段:列出绑定到该地址的登录态(`location_id`),支持在此处直接新增登录态(新增时 `location_id` 自动填该地址),支持解绑/删除。
- R5.2 `LocationView.vue` 地址卡片下新增"推送 spt"段:spt 列表 + 新增/编辑/删除/启停/设默认 + "测试推送"按钮,**无验证码**,直存。
- R5.3 `GrabLoginView.vue`(原独立登录态页)处理:登录态变为"地址下管理"后,该页要么改为按地址分组的视图,要么下线并把入口收到地址页。**取向下线**——登录态统一在地址页管理,避免两处入口冲突(老记录 `location_id` 为空的可在此页保留只读迁移提示)。
- R5.4 接口走现有 `src/api/index.ts`(axios,token 从 localStorage),新增地址维度接口对接。

## Acceptance Criteria

- [ ] `grab_login_state` 有 `location_id` 字段,DDL 在 `ddl.sql` 增量可执行。
- [ ] `location_push_target` 表存在,同地址可存多个 spt。
- [ ] `PushService.getPushTargets(locationId)` 命中地址 spt 时返回该地址去重列表,无配置时回退 `user.spt`。
- [ ] 监控命中 → 推送到命中的 `locationId` 绑定的所有 spt;无地址 spt 时回退 `user.spt`。
- [ ] 抢单命中 → 推送到该次 `grab_config.locationId` 绑定的所有 spt;无则回退 `user.spt`。
- [ ] JWT 过期提醒 → 推送到登录态 `location_id` 绑定的 spt;老记录无 `location_id` 时回退 `user.spt`。
- [ ] spt 绑定接口不触发验证码;提供独立测试推送接口。
- [ ] 老 `grab_login_state` 记录 `location_id` 为空,不报错、回退兜底。
- [ ] 前端地址管理页(`xiaocan-front-main/src/views/LocationView.vue`):登录态绑定段 + spt 绑定段可用,测试推送可收到。
- [ ] 后端 `mvn -o compile` 通过(本地,不在生产服务器执行);前端 `npm run build`(type-check 通过)。
- [ ] 编译通过(`mvn -o compile`,本地,不在生产服务器执行)。

## Out of Scope

- 管理员账户体系(已否决)。
- 账号+密码登录重构(已否决)。
- 验证码迁 Redis(已取消)。
- 推送并发限流/重试的高级策略(自用记日志即可,本任务不引入)。
- 跨地址分组绑定(已确认登录态不跨地址)。

## Open Questions

- (无阻塞。前端路径已确认 `C:\D\AI\Projects\xiaocan\xiaocan-front-main`,已存记忆。)
