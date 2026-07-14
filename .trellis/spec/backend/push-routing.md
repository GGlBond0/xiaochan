# Push Routing Guidelines

> 推送（WxPusher）目标路由约定：按地址(location)路由多 spt，user.spt 兜底。

---

## Overview

推送目标 spt **不再挂在系统用户(user)上单值取用**，而是按地址(location)路由。一个地址可绑定多个 WxPusher spt，同一地址下的多个抢单登录态命中后统一推送到该地址绑定的 spt。`user.spt` 保留为兜底（地址无 spt 配置时回退）。

统一收口到 `PushService`，**所有业务推送调用点禁止直接 `MessageHttp.sendMessage(user.getSpt(), ...)`**。

---

## Data Model

- `grab_login_state.location_id`（BIGINT，可空）：登录态归属地址。同一登录态不跨地址抢单。老记录留空。
- `location_push_target`（新建表）：地址推送目标。`location_id` + `spt` + `enabled` + `sort`，一地址多 spt。
- `user.spt`：保留，作为地址无 spt 配置时的兜底。

---

## PushService Contract

```java
List<String> getPushTargets(Long locationId);  // 地址启用 spt 去重 + 空则回退 user.spt
void pushToLocation(Long locationId, String content, String summary);  // 业务推送主入口
void pushToUser(Integer userId, String content, String summary);        // 无地址语境兜底
void testPush(Long locationId);                                             // 测试推送，不写历史
```

- 所有方法**显式接收参数**，不依赖 HTTP 请求上下文——定时任务线程可安全调用（见 error-handling.md「定时任务复用的方法不能依赖 HTTP 请求上下文」）。
- 多 spt 串行推送，单个失败 `log.error` 不中断，不重试（自用闭环）。
- 去重：同 spt 多行只推一次。

---

## Routing by Call Site

| 调用点 | locationId 来源 | 走法 |
|--------|----------------|------|
| 监控 `BaseTask.sendMessage` | 入参 `LocationEntity.id` | `pushToLocation` |
| 抢单 `GrabServiceImpl.push` | `grab_config.locationId` | `pushToLocation`，空回退 `pushToUser` |
| JWT 过期 `GrabJwtExpireTask` | `grab_login_state.location_id` | `pushToLocation`，空回退 `pushToUser` |
| 注册验证码 `SptService.sendSptCode` | 入参 spt | 直接 `MessageHttp.sendMessage`（注册流程，不走 PushService） |

`MessageHttp.sendMessage` 仅允许出现在 `PushServiceImpl`（收口）与 `SptService`（注册验证码）。

---

## Backward Compatibility

- 地址无 `location_push_target` → `getPushTargets` 回退 `user.spt`，老用户无感知。
- 老 `grab_login_state.location_id` 空 → 抢单/JWT 过期推送回退 `pushToUser`，不报错。
- 改造后未配置地址 spt 的场景行为与改造前一致。

---

## Common Mistakes

- **不要在业务调用点直接 `user.getSpt()` 推送**——绕过 `PushService` 会丢失地址路由与兜底逻辑。
- **不要在 `PushService` 内调 `getByCurrentRequest()`**——定时任务（JWT 过期、CRON 抢单）线程无 HTTP 上下文，必须显式传 `userId`/`locationId`。
- **spt 绑定不需要验证码确认**——spt 非敏感凭证（填错只丢推送），验证码仅用于注册证明身份。
- 登录态管理入口在地址页（`LocationView`），老的独立登录态页已收口为只读 + 迁移提示，避免两处入口冲突。
