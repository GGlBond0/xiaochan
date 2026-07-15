# 技术设计

## 数据模型变更

### `grab_config` 新增字段（DDL 追加到 `ddl.sql`）

```sql
ALTER TABLE `grab_config`
  ADD COLUMN `auto` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否监控自动抢单产生: 0-否(手动/定时), 1-是(立即抢,不进前端列表)',
  ADD COLUMN `store_name` varchar(128) NULL DEFAULT NULL COMMENT '活动快照: 商家名',
  ADD COLUMN `promo_detail` varchar(64) NULL DEFAULT NULL COMMENT '活动快照: 优惠明细 如 满20返15',
  ADD COLUMN `start_time` varchar(8) NULL DEFAULT NULL COMMENT '活动快照: 时段开始 HH:MM',
  ADD COLUMN `end_time` varchar(8) NULL DEFAULT NULL COMMENT '活动快照: 时段结束 HH:MM';
```

`GrabConfigEntity` 增对应字段：`Boolean auto`、`String storeName`、`String promoDetail`、`String startTime`、`String endTime`。
`GrabConfigVO` 同步增这5个字段（`auto` 也透出，前端过滤兜底）。

> `start_time`/`end_time` 用 `varchar(8)` 存 `HH:MM`，与 `StoreInfo` 的字符串格式一致，避免类型转换。

## 后端改动

### A. `AutoGrabServiceImpl.tryCreateFromMonitor` 改造

当前逻辑：立即抢分支 `executeAt = now` → save → `refresh()` → 被判过期跳过。

新逻辑（按用户决策"落库但不进前端/不调度，直接执行"）：

1. 开关门禁 / 仅美团 / promotionId 非空：不变。
2. **防重**（修正）：查同 userId + promotionId + 当天 + `auto=1` 且 `status` 为 ENABLE（即尚未执行完成）的记录存在则跳过。注意——立即抢执行后 `doGrab` 成功会置 `DISABLE`，失败也可能保留 `ENABLE`。为避免失败任务永久挡，防重只看「当天 auto=1 且 lastGrabTime 为空（尚未真正执行过）」的占位记录；有 lastGrabTime 说明已执行过（成功或失败），允许新命中再抢。

   实际更简洁的语义：**防重 = 当天 auto=1 且 status=ENABLE 且 lastGrabTime IS NULL 的占位**。doGrab 执行时（无论成败）会写 lastGrabTime/lastResult，占位即"已消费"，不再挡后续。

   > 但 doGrab 成功会置 DISABLE，失败时呢？看 GrabServiceImpl L246-290：失败重试耗尽只推送+break，**不置 DISABLE，不写 lastGrabTime**（lastGrabTime 仅成功时写）。所以"失败未写 lastGrabTime"的占位会一直 ENABLE+lastGrabTime NULL → 永久挡。需要：立即抢分支执行结束后，无论成败都把占位记录的 lastGrabTime 写上（或置 DISABLE）。方案：在 AutoGrab 触发 doGrab 后，update 占位 `lastGrabTime=now, lastResult=...`，让占位标记为"已消费"。

3. **落占位**：新建 `GrabConfigEntity`，`auto=true`，`status=ENABLE`，冗余 `storeName/promoDetail/startTime/endTime` 快照，`executeAt=now`（仅留痕，不用于调度），`cron=null`。save。
4. **异步执行**：提交到独立线程池（或 `taskScheduler`）跑 `grabService.doGrab(entity, "AUTO")`，不调 `grabCronScheduler.refresh()`。
5. 执行回调（doGrab 返回后）：update 占位记录 `lastGrabTime=now, lastResult=<汇总>, status=<DISABLE if 成功 else ENABLE>`，确保占位"已消费"，防重不再误挡。
6. 登录态过期：保持原 pushExpireReminder 不变。
7. 定时抢分支：保持原逻辑建任务 + refresh()，但 `auto=false`（定时任务要进前端列表可查看），冗余快照字段。

### B. 异步执行线程池

`doGrab` 内部有重试 sleep + 上游 HTTP，单次可能耗时数秒。不能阻塞 monitor-cron。方案：在 `AutoGrabServiceImpl` 自建一个专用 `ExecutorService`（独立于 `taskScheduler`，避免与 cron 调度线程互相影响：立即抢的阻塞/重试不应挤占 cron 调度槽，反之亦然）。用 `Executors.newCachedThreadPool`（自动伸缩，适合短时突发；长空闲回收）。`@PreDestroy` shutdown 优雅停止。submit 的是 `doGrab`，返回 `GrabResultVO` 后在回调里更新占位。

线程安全：`XiaochanHttp` 在 `GrabServiceImpl` 是 `new XiaochanHttp()` 字段（每次 new 的实例？实际是字段初始化一次），但 doGrab 用的是该 service 单例的 http 字段——需确认 `XiaochanHttp` 是否线程安全。若其内部无共享可变状态（通常基于每次构造请求），多线程并发 doGrab 应安全。**实现时需 grep 确认 XiaochanHttp 是否有共享可变字段；若有则每次 doGrab 前新建实例或加同步。** 这是实现期 check 项。

### C. `GrabServiceImpl.listByUserId` 过滤自动记录

```java
return this.lambdaQuery().eq(GrabConfigEntity::getUserId, uid)
    .ne(GrabConfigEntity::getAuto, true)   // 不展示自动抢单占位
    .orderByDesc(GrabConfigEntity::getId).list()...
```
手动建的配置 `auto` 默认 0/null，`ne true` 保留。同时 VO 透传快照字段。

### D. 手动新建/编辑也存快照

`GrabConfigDTO` 增 `storeName/promoDetail/startTime/endTime`（可选，前端"从活动列表选"带入）。`addUpdateConfig` 的 `BeanUtils.copyProperties` 会自动落到 entity。前端选活动时把 store 快照塞进表单一起提交。

## 前端改动（`GrabConfigView.vue`，独立仓库 xiaocan-front-main）

1. 表格新增列（在「活动ID」后）：
   - 商家名 `storeName`（show-overflow-tooltip）
   - 优惠 `promoDetail`
   - 时段：复用现有 `isAllDay(row)`/`timeRange(row)`。全天活动显示「全天」tag，否则 `startTime-endTime`。但列表 row 的快照字段来自后端 `storeName/startTime/endTime`（活动选择器那个 row 不同），需在 `GrabConfigView.vue` 内新增基于后端快照字段的展示函数：row.startTime/endTime 缺一即视为全天。
2. 表单 `form` 增 `storeName/promoDetail/startTime/endTime`，`pickStore` 时从 row 带入；`handleSubmit` payload 带上。
3. 列表加载无需改接口（`/api/grab/config/list` 已返回新字段）。

## 数据流

```
监控命中 → BaseTask.triggerAutoGrab → AutoGrabService.tryCreateFromMonitor
  ├─ 定时抢(未到点): save(auto=0, 快照) → cronScheduler.refresh → [前端列表可见]
  └─ 立即抢(在时段): save(auto=1, 快照, 占位) → 异步 doGrab("AUTO") → 回调更新占位 lastGrabTime/lastResult
                    [不进 cron, 不进前端列表]
```

## 兼容性 / 回滚

- 旧 `grab_config` 数据 `auto` 默认 0，不影响现有手动任务。
- 回滚 DDL：`ALTER TABLE grab_config DROP COLUMN auto, DROP COLUMN store_name, ...`。
- 回滚代码：revert 两个仓库的 commit；僵尸任务需手动清理（见下）。
- 一次性数据修复：生产已存在的 `auto=1` 僵尸本次不存在（旧版未加 auto 字段）；上线后若仍有遗留，靠新的"lastGrabTime 已消费"语义自然放行。

## 僵尸任务清理（上线时附带）

旧版产生的"executeAt 已过期+ENABLE"grab_config（如日志中 configId 10/11/12 类，但那些已因防重挡了后续）。上线后这些旧记录 `auto=0`（旧无此字段→0）仍会出现在前端列表且无法执行。提供一条清理 SQL（手动执行）：
```sql
UPDATE grab_config SET status='DISABLE'
WHERE status='ENABLE' AND cron IS NULL AND execute_at IS NOT NULL
  AND execute_at < NOW() AND last_grab_time IS NULL;
```
（仅清理一次性过期且从未执行过的；写入 implement.md 作为可选步骤）

## 风险

- 异步 doGrab 并发线程安全依赖 XiaochanHttp（实现期验证）。
- 防重"已消费"语义若 doGrab 回调 update 失败，占位可能永久 ENABLE+lastGrabTime NULL。加 try-catch + 兜底：执行前就先把 lastGrabTime 标一个"执行中"值，或用单独 `auto_status`。简化：submit 前 update `lastResult='执行中'`，防重看 `lastResult IS NULL`。
  → **最终防重判定**：当天 auto=1 且 `lastGrabTime IS NULL AND lastResult IS NULL`（完全未触碰）。doGrab 之前先把 lastResult 设为"执行中"，之后更新最终结果。这样即使回调失败，"执行中"标记也阻止重复抢。
