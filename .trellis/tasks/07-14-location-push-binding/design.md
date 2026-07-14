# Design — 多spt推送-按地址绑定登录态与推送通道

## 1. 架构与边界

### 1.1 双仓库改动
- **后端** `xiaocan-main`(本仓库):数据模型、推送收口、3 个调用点改路由、地址维度 CRUD 接口、测试推送接口。
- **前端** `C:\D\AI\Projects\xiaocan\xiaocan-front-main`(Vue3 + Element Plus + Vite + TS):`LocationView.vue` 地址卡片扩两段;`GrabLoginView.vue` 下线/迁移。

### 1.2 核心思想
推送目标从"user 级单值 spt"下沉到"location 级多 spt";登录态从"user 级独立"下沉到"location 级归属"。所有推送按 `locationId` 路由,`user.spt` 保留为兜底。

## 2. 数据模型

### 2.1 `grab_login_state` 新增字段
```sql
ALTER TABLE grab_login_state ADD COLUMN location_id BIGINT NULL COMMENT '所属地址id(location.id)，老记录留空';
```
- 单值,可空。空表示老记录(未迁移),推送回退 `user.spt`。
- 语义:一个登录态固定属一个地址(用户已确认不跨地址抢单)。

### 2.2 新建 `location_push_target`
```sql
CREATE TABLE location_push_target (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  location_id BIGINT NOT NULL COMMENT '地址id',
  spt VARCHAR(255) NOT NULL COMMENT 'WxPusher spt',
  remark VARCHAR(255) NULL COMMENT '备注',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认目标(语义预留,当前实现按enabled全推)',
  sort INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0
);
CREATE INDEX idx_location_push_target_loc ON location_push_target(location_id);
```
- 一地址多 spt;推送时取 `enabled=1` 去重。
- `is_default` 字段保留但当前实现不依赖(全推),为将来"默认通道优先"留口。

### 2.3 `user.spt` 保留
不动。`PushService` 在地址无 spt 配置时回退 `user.spt`。

### 2.4 迁移
- 老 `grab_login_state.location_id` 留空,不写自动迁移。
- 前端在地址页引导用户重新绑定;`GrabLoginView.vue` 老入口保留只读迁移提示。
- 迁移 SQL 全部进 `ddl.sql` 增量。

## 3. 推送收口:PushService

新建 `io.github.xiaocan.service.PushService`(接口)+ `PushServiceImpl`。

```java
// 按地址取推送目标:地址启用spt去重 + 无配置回退user.spt
List<String> getPushTargets(Long locationId);

// 按地址推送(遍历逐个推,失败记日志不中断)
void pushToLocation(Long locationId, String content, String summary);

// 兜底:无地址语境(纯用户级提醒)按user.spt推
void pushToUser(Integer userId, String content, String summary);

// 测试推送:指定locationId发一条测试消息,不入业务历史
void testPush(Long locationId);
```

- 内部仍调 `MessageHttp.sendMessage(spt, content, summary)`(HTTP 同步,沿用现有单点调用语义)。
- 去重:同一 spt 在多行出现只推一次(Set 去重)。
- 部分失败:逐个 try/catch,记 `log.error`,不重试、不抛(自用闭环)。
- 并发:本任务不引入线程池/限流,串行推送多 spt(自用通道数少,够用)。

## 4. 推送调用点改路由

| 调用点 | 现状 | 改造 |
|--------|------|------|
| `BaseTask.java:190` 监控推送 | `userService.getById(userId).getSpt()` 单值 | 入参已有 `LocationEntity`→`locationId`,改 `pushService.pushToLocation(locationId, body, summary)` |
| `GrabServiceImpl.java:629 push()` 抢单 | `user.getSpt()` 单值 | 把 `grab_config.locationId` 透传到 push,改 `pushToLocation(locationId,...)`;无 locationId 回退 `pushToUser` |
| `GrabJwtExpireTask.java:87` JWT 过期 | `user.getSpt()` 单值 | 登录态新增 `location_id`,改 `pushToLocation(state.getLocationId(),...))`;老记录无 `location_id` 回退 `pushToUser` |
| `SptService.java:43` 注册验证码 | 按入参 spt 推 | 不动 |

### 4.1 抢单调用链 locationId 透传
`GrabServiceImpl.push(user, summary, body)` 现签名只有 user。改造为 `push(locationId, summary, body)`(或保留 user 但加 locationId)。调用 `push` 的上层已有 `GrabConfigEntity`(含 `locationId`),透传即可。需定位 `push` 所有调用点一并改签名。

### 4.2 JWT 过期 locationId 来源
`GrabJwtExpireTask.checkOne(state)` 已有 `GrabLoginStateEntity state`,直接 `state.getLocationId()`。新增字段后即可。

## 5. 地址维度绑定/管理接口

### 5.1 登录态带 locationId
- `GrabLoginStateDTO` 加 `Long locationId`(可空,新增时由地址页填)。
- `saveLoginState(dto, id)` 校验:`locationId` 非空时校验属于当前用户。
- `listLoginState` VO 带回 `locationId`、`locationName`(地址名,前端分组展示用)。

### 5.2 地址推送 spt CRUD
新增 `LocationPushTargetController`(`@RequestMapping("/api/location/push-target")`):
- `GET /api/location/{locationId}/push-target` 列表
- `POST /api/location/{locationId}/push-target` 新增
- `PUT /api/location/push-target/{id}` 编辑(remark/enabled/sort)
- `DELETE /api/location/push-target/{id}` 删除
- `POST /api/location/{locationId}/push-target/test` 测试推送

- 全部校验 `locationId` 属当前用户(防越权)。
- **无验证码**:直接 CRUD。
- 测试推送:取该地址启用 spt,发固定文案"【测试推送】地址 xxx 推送通道测试成功",不写业务历史。

### 5.3 登录态按地址查
`GET /api/grab/login-state/list` 已存在,VO 加 `locationId`/`locationName`。前端在地址页按 `locationId` 过滤展示本地址登录态。

## 6. 前端设计(xiaocan-front-main)

### 6.1 `LocationView.vue` 扩段
每个 `address-card` 内,在"删除"按钮上方/下方加两段(可折叠):

**段 A — 抢单登录态**
- 列表:该地址 `locationId` 下的登录态(从 `listLoginState` 过滤)。
- "新增登录态"按钮:打开对话框,`locationId` 隐藏填该地址,复用现有 `rawHeaders` 抓包录入流程(对接 `POST /api/grab/login-state` 带 `locationId`)。
- 每条:更新(复用 openEdit)/解绑(将 `locationId` 置空或删除)/删除。

**段 B — 推送 spt**
- 列表:该地址 spt 行(`GET /api/location/{id}/push-target`)。
- "新增 spt":输入框(spt)+ 备注,直存(无验证码)。
- 每条:启停 switch、编辑备注、"测试推送"按钮(调 `/test`)。
- 反馈:测试推送后 ElMessage 提示"已发送,请到对应微信查看"。

### 6.2 `GrabLoginView.vue` 收口
- 取向下线为独立入口:登录态改为地址下管理后,该页改为只读列表 + 迁移提示("请在地址管理页管理登录态")。
- 老记录(`locationId` 为空)在此页可见,提示绑定到某地址。
- 路由 `router/index.ts` 移除或改为重定向到地址页(具体保留重定向还是删除导航入口,实现时定)。

### 6.3 API
走现有 `src/api/index.ts` axios 实例,新增上述地址维度接口调用。token 已由 axios 拦截器从 localStorage 带。

## 7. 兼容与回滚

### 7.1 兼容
- `user.spt` 保留,无 spt 配置的地址自动回退,老用户无感知。
- 老 `grab_login_state.location_id` 空,JWT 过期/抢单推送回退 `user.spt`,不报错。
- 4 个推送调用点改造后,未配置地址 spt 的场景行为与现状一致(回退单值)。

### 7.2 回滚
- DDL 增量,不动老表结构(只加列、加表)。回滚:`ALTER TABLE grab_login_state DROP COLUMN location_id` + `DROP TABLE location_push_target` + 还原 3 个调用点代码。
- 推送收口到 `PushService` 是纯重构,回滚只需把调用点改回 `user.getSpt()`。

### 7.3 风险点
- `GrabServiceImpl.push` 签名改动需覆盖所有调用点(漏改一处会编译失败,可控)。
- `GrabJwtExpireTask.reminded` Map 仍按 `loginStateId` 去重,不受影响。
- 前端两处入口(地址页 vs 老登录态页)若并存会重复管理冲突 → 靠 R5.3 下线老入口避免。

## 8. 构建与验证
- 后端:`mvn -o compile`(本地,见记忆 [[prod-build-avoid-server]] 禁止生产服务器构建)。
- 前端:`npm run build`(含 `vue-tsc` type-check)。
- 端到端:地址页绑定 1 个 spt + 1 个登录态 → 手动触发一次抢单/监控 → 验证推送到该 spt;测试推送按钮 → 微信收到。

## 9. 父子任务评估
本任务交付物:**后端推送收口**(可独立验证)、**前端地址页扩段**(可独立验证)前后端耦合度高(前端依赖后端接口),不建议拆父子任务。作为单一任务按"后端先行接口 → 前端对接"顺序在 `implement.md` 里编排。
