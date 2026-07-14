# 技术设计：监控配置自动建立抢单任务

## 边界

本特性把"监控命中门店活动"与"抢单任务创建"两个原本独立的子系统打通：监控执行体命中后，按配置自动组装并落库 `grab_config`，注册进 `GrabCronScheduler`。

- 触发点在监控执行体内（同步、命中后即时），**不新增 `@Scheduled`**。
- 复用 `GrabCronScheduler` 的一次性/定时注册机制，不新建调度器。
- 复用 `login_state` 统一池与 `loginStateService.toGrabAuth`。

## 数据模型变更

### `monitor_config` 新增列（ddl.sql）

```sql
ALTER TABLE monitor_config
  ADD COLUMN auto_grab TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '命中后自动建抢单任务 0否1是',
  ADD COLUMN grab_login_state_id INT NULL
    COMMENT '自动抢单所用登录态id，指向login_state.id';
```

不建外键硬约束（与现有 `grab_config.login_state_id` 一致风格，逻辑外键）。可选索引 `idx_grab_login_state_id` 暂不加（查询不按它过滤）。

### Entity / DTO / VO 字段映射

| 对象 | 新增字段 | 类型 |
|---|---|---|
| `MonitorConfigEntity` | `autoGrab` | `Boolean` |
| `MonitorConfigEntity` | `grabLoginStateId` | `Integer` |
| `monitorConfigDTO` | `autoGrab` | `Boolean`（默认 false） |
| `monitorConfigDTO` | `grabLoginStateId` | `Integer`（`autoGrab=true` 时必填） |
| `NotifyConfigVO` | `autoGrab` / `grabLoginStateId` | 回显同型 |

`MonitoryConfigServiceImpl.addUpdateConfig` 在校验段增加：
- `autoGrab != null && autoGrab` → `grabLoginStateId` 非空，且 `loginStateService.getEntity(userId, grabLoginStateId)` 存在且未过期（可选即时校验，过期给前端提示）。
- `autoGrab` 为 null/false → `grabLoginStateId` 忽略（置 null 存库）。

## 核心组件：AutoGrabService

新增 `io.github.xiaocan.service.AutoGrabService` + `impl.AutoGrabServiceImpl`，单一入口：

```java
/**
 * 监控命中后自动建立抢单任务。
 * @param config 命中的监控配置（含 autoGrab/grabLoginStateId/locationId/userId）
 * @param store  命中的门店活动（含 promotionId/startTime/endTime/type）
 * @return 建成的 GrabConfigEntity id；未建返回 null（已存在/过期/非美团/登录态过期）
 */
Long tryCreateFromMonitor(MonitorConfigEntity config, StoreInfo store);
```

### tryCreateFromMonitor 内部流程

```
1. 开关与平台门禁
   if (!Boolean.TRUE.equals(config.getAutoGrab())) return null;
   if (store.getType() == null || store.getType() != 1) return null;   // 决策A：仅美团

2. 防重（一天一活动一次）
   exists = grabConfigMapper.exists(
       userId = config.getUserId(),
       promotionId = store.getPromotionId(),
       date = today,
       status = ENABLE, deleted = 0);
   if (exists) return null;

3. 登录态校验
   loginState = loginStateService.getEntityByIdAndUser(config.getGrabLoginStateId(), userId);
   if (loginState == null || loginState.getExpireAt() == null
       || loginState.getExpireAt().isBefore(now)) {
       // 跳过建任务 + 推提醒（复用 PushService 收口）
       // 注：实现期增强——loginState==null（账号已删除）也推提醒(摘要"自动抢单账号不可用"，账号名"(已删除)")，
       //     比原设计「仅过期才推」对用户更友好。已删除与过期合并为同一提醒通道。
       String summary = "自动抢单账号不可用";
       String name = loginState == null ? "(已删除)" : loginState.getName();
       String content = "自动抢单账号「" + name + "」已过期或不存在，监控命中的活动未自动抢单，请重新抓包录入登录态。";
       if (config.getLocationId() != null) {
           pushService.pushToLocation(config.getLocationId(), content, summary);
       } else {
           pushService.pushToUser(config.getUserId(), content, summary);
       }
       return null;
   }

4. 时间判断（当天 + HH:MM 拼 LocalDateTime）
   today = LocalDate.now();
   start = today.atTime(parseHHMM(store.getStartTime()));
   end   = today.atTime(parseHHMM(store.getEndTime()));
   LocalDateTime executeAt;
   if (now.isBefore(start))       executeAt = start;          // 定时到点抢
   else if (now.isBefore(end))    executeAt = now;            // 立即抢
   else                           return null;                // 已过期，不建

5. 组装 GrabConfigEntity（复用 addUpdateConfig 默认值约定）
   entity = new GrabConfigEntity();
   entity.setUserId(config.getUserId());
   entity.setLoginStateId(config.getGrabLoginStateId());
   entity.setLocationId(config.getLocationId());
   entity.setPromotionId(store.getPromotionId());
   entity.setStorePlatform(1);
   entity.setIfAdvanceOrder(false);
   entity.setLeadMs(0);
   entity.setEnableRetry(true);
   entity.setMaxRetry(3);
   entity.setRetryIntervalMs(500);
   entity.setSilkId(0);
   entity.setStatus(MonitorConfigStatusEnums.ENABLE);   // 已确认：GrabConfigEntity.status 用此枚举
   entity.setExecuteAt(executeAt);
   grabConfigMapper.insert(entity);                    // createTime 由 MP 自动填充，用于防重

6. 注册调度
   grabCronScheduler.refresh(entity.getId());

   return entity.getId();
   // 注：GrabConfigEntity 无 remark 字段（已核实）。grab_history 由 doGrab 执行时写入
   //     （triggerType=ONESHOT/CRON），建任务阶段不写 history、不写来源标识。
   //     自动任务与手工任务在表上无显式区分列；如需区分后续再加，本期不做。
```

### 依赖注入

`AutoGrabServiceImpl` 依赖：`GrabConfigMapper`、`LoginStateService`、`GrabCronScheduler`、`PushService`（过期提醒收口）。全部已有 bean，无新基础设施。不依赖 GrabHistory（建任务阶段不写 history）。

### 防重查询实现

`GrabConfigMapper` 加一个查询（LambdaQuery 或 wrapper）：
```
SELECT 1 FROM grab_config
WHERE user_id = ? AND promotion_id = ? AND status='ENABLE' AND deleted=0
  AND DATE(create_time) = CURDATE()
LIMIT 1
```
注意：现有 `grab_config` 表是否有 `create_time` 字段需确认（若无，用 `execute_at` 的日期或加 `created_at` 条件）。→ 实现阶段先读 `GrabConfigEntity` 核对字段名。

## 触发点接入

### `StoreTask`（STORE_ACTIVITY + STORE_KEYWORD）
在推送通知循环里、对每个命中 `StoreInfo`：
```java
if (Boolean.TRUE.equals(config.getAutoGrab())) {
    try { autoGrabService.tryCreateFromMonitor(config, store); }
    catch (Exception e) { log.warn("auto-grab failed promotionId={} : {}", store.getPromotionId(), e.getMessage()); }
}
```
包 try/catch 防止建任务异常影响主通知流程。

### `MinimumPayService`（MINIMUM_PAY）
同样在命中推送后接入。

**两个执行体都持有当前 `MonitorConfigEntity` 本体**，直接读 `autoGrab`/`grabLoginStateId`。接入位置：推送通知之后，与通知并列、互不阻塞。

## 时间解析辅助

`parseHHMM(String)` → LocalTime，解析失败回退到 `00:00`（start）或 `23:59`（end），并 log warn。`StoreInfo.startTime`/`endTime` 来自上游 `formatStartEndTime`，格式可靠，但容错保留。

## 跨子系统契约

| 契约点 | 约定 |
|---|---|
| `StoreInfo.promotionId` → `GrabConfigEntity.promotionId` | 同一上游字段，Integer 直传 |
| `StoreInfo.type==1` 才建 | 决策 A |
| `monitor_config.locationId` → `grab_config.locationId` | 直传 |
| `monitor_config.grabLoginStateId` → `grab_config.loginStateId` | 直传，同一 `login_state` 池 |
| `grab_config.create_time` = 当天 | 防重去重字段（MP 自动填充） |

## 向后兼容

- `auto_grab` 默认 0，老配置全部视为关闭，命中仅通知。
- `AutoGrabService` 异常被 try/catch 吞掉 + warn，不污染监控主流程。
- 新字段在 `monitorConfigDTO` 可选，老前端不传也兼容（默认 false）。

## 部署与回滚

- DDL 先上（加列，默认 0，无锁表风险，1.7G 小机可接受 ALTER）。
- 后端发版（含新 service + 触发接入）。
- 前端发版（表单开关 + 账号下拉）。
- 回滚：前端隐藏开关 → 后端 `autoGrab` 永远 false → DDL 不回滚（列保留无副作用）。任何一步可独立回退。
- 遵循 [[prod-build-avoid-server]]：本地构建或 GitHub Actions，不在服务器跑 mvn；部署路径/服务名以 [[deploy-topology]] 实测为准。

## 已确认项（Review Gate 1 完成）

1. `grab_config` 含 `create_time`(LocalDateTime) 字段 —— 防重用 `DATE(create_time)=CURDATE()`。
2. `GrabConfigEntity.status` 类型是 `MonitorConfigStatusEnums`（与监控同枚举），ENABLE 即可。
3. `GrabConfigEntity` 无 `remark` 字段；建任务阶段不写 grab_history（由 doGrab 写）。
4. 过期提醒复用 `PushService.pushToLocation/pushToUser`（已收口，见 `GrabJwtExpireTask`）。
