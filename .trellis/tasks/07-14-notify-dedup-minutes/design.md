# Design: 通知去重与过期清理按分钟数

## 数据流与边界

- 用户在监控配置页填 `dedupMinutes`（N）→ 存 `monitor_config.ext_config` JSON → `MinimumPayExtNotifyConfig.dedupMinutes`。
- 监控执行（`MinimumPayService` 经 `BaseTask.runSingle`）：
  1. `filterStoreInfos` 去重用 N 分钟窗口（替换永久去重）
  2. 命中后 `savePushedHistory` 写 `store_pushed_history`
  3. 清理本配置超过 N 分钟的旧 `store_pushed_history`（独立于是否命中，每次执行都清）
- 通知记录页：前端读所选监控配置的 `dedupMinutes` → 作为 `recentMinutes` 传 `/api/notify-history/page` → `pageByUser` 按 `create_time >= now()-N min` 过滤。

## 变更点

### 后端

1. `MinimumPayExtNotifyConfig`：新增 `Integer dedupMinutes`，默认 60，`@Min(1)`。`AbstractExtNotifyConfig` 不动。

2. `StorePushedHistoryService` / `Impl`：
   - 新增 `StorePushedHistoryEntity findByNotifyIdAndStoreIdWithinMinutes(Integer notifyId, Integer storeId, int minutes)`：`notify_config_id=? and store_id=? and create_time >= now()-minutes` limit 1。
   - 新增 `int deleteByNotifyIdOlderThanMinutes(Integer notifyId, int minutes)`：`notify_config_id=? and create_time < now()-minutes` 删除，返回删除数（用于日志）。用 `lambdaUpdate().eq(notifyConfigId).lt(createTime, cutoff).remove()`。
   - 保留旧 `findByNotifyIdAndStoreIdAll` / `Today`（不删，兼容）。
   - `pageByUser`：`NotifyHistoryQueryDTO` 新增 `Integer recentMinutes`；当非空时追加 `.ge(createTime, now()-recentMinutes)` 过滤。

3. `MinimumPayService.filterStoreInfos`：第 50 行去重由
   `findByNotifyIdAndStoreIdAll(...) == null`
   改为
   `findByNotifyIdAndStoreIdWithinMinutes(notifyConfig.getId(), storeInfo.getStoreId(), extNotifyConfig.getDedupMinutes()) == null`。
   `dedupMinutes` 为空时按默认 60（在 ext config 默认值兜底，或调用前 `Optional` 兜底）。

4. 清理逻辑：新增 `MinimumPayService` 内方法（或在 `BaseTask` 提供钩子）。选择放在 `MinimumPayService` 重写 `afterSuccess` 不够——清理需在**每次执行都做**（无命中也要清），而 `afterSuccess` 仅命中时调用。因此：
   - 方案：在 `BaseTask.runSingle` 的 `finally` 之后、或 `filterStoreInfos` 之前调用子类清理钩子 `cleanupExpired(notifyConfig)`。给 `BaseTask` 加空方法 `protected void cleanupExpired(MonitorConfigEntity c){}`，`MinimumPayService` 重写：解析 ext config 取 N，调 `storePushedHistoryService.deleteByNotifyIdOlderThanMinutes`。
   - 清理放在 `runSingle` try 块内、`fetchStoreInfos` 之前（先清理过期再判断），确保无命中也清理。

5. `NotifyHistoryQueryDTO`：新增 `Integer recentMinutes`。

### 前端

6. `MonitorConfigView.vue`：
   - `form.minimumPayExtNotifyConfig` 增加 `dedupMinutes: 60`。
   - `resetForm` 同步重置 `dedupMinutes: 60`。
   - `showEditDialog` 回填 `dedupMinutes`（`?? 60`）。
   - MINIMUM_PAY 模板块加 `el-form-item label="去重/过期分钟"` + `el-input-number v-model="form.minimumPayExtNotifyConfig.dedupMinutes" :min="1" :step="10"`，提示「同店 N 分钟内不重复通知；超过 N 分钟的旧记录自动删除且记录页不再显示」。
   - 列表卡片/详情可选展示该值（次要，可不做）。

7. `NotifyHistoryView.vue`：
   - `loadNotifyConfigList` 已加载监控配置；`handleSearch` 提交 `searchForm` 时，根据所选 `notifyConfigId` 从 `notifyConfigList` 找对应配置的 `minimumPayExtNotifyConfig.dedupMinutes`，作为 `recentMinutes` 传入 `searchForm`（无配置/无值时不传，兼容）。
   - `searchForm` 增 `recentMinutes`。
   - 不加独立筛选框（N 统一来自监控配置）。

## 契约 / 兼容

- `dedupMinutes` 缺省（旧 ext_config 无此字段）→ 默认 60 分钟，行为：从「永久去重」变为「60分钟去重」——这是行为改变，但符合需求；旧 14 条记录中超过 60 分钟的会在下次执行时被清理。
- 不改 DB schema，不加列/索引（`create_time`、`notify_config_id` 已有索引，清理 SQL 走 `notify_config_id` 索引足够）。
- 清理是物理删除（用户要求「直接删除」），不可逆，但本就是用户要的过期清理。

## 风险与权衡

- 清理 SQL 每次监控执行跑一次，按 notify_config_id 删，量小（单配置门店数有限），无性能问题。
- 时区：`now()` 用服务器本地时间，与 `create_time`（`CURRENT_TIMESTAMP`）一致，无时区偏差。
- `findByNotifyIdAndStoreIdWithinMinutes` 用 `create_time >= now()-N`，跨天也成立（不像 Today 按自然日截断）。
- 只改 MINIMUM_PAY；STORE_ACTIVITY/STORE_KEYWORD 仍用各自原去重，不受影响。

## 验证

## 设计变更（2026-07-14）：N 从「配置级」改为「用户全局级」

**问题**：原方案把 dedupMinutes 放每个监控配置的 ext_config，多配置时记录页选「全部配置」无法确定 N，且 N 属于「展示/清理策略」而非单配置业务参数，配置级存放语义不当。

**新方案**：N 作为**用户全局值**，存 `user` 表新列 `notify_dedup_minutes`（INT 默认 60，NOT NULL）。所有监控配置共用同一 N。

### 变更点（覆盖上文「变更点」章节）

1. **DB schema**：`ALTER TABLE user ADD COLUMN notify_dedup_minutes INT NOT NULL DEFAULT 60`。同步更新 `ddl.sql` 的 user 表定义。生产库执行 ALTER（已有用户回填 60）。

2. **`UserEntity`** 加 `private Integer notifyDedupMinutes = 60;`。

3. **`MinimumPayExtNotifyConfig`**：移除 `dedupMinutes` 字段（不再配置级）。已部署的 ext_config 里若有该字段，反序列化忽略即可（fastjson 忽略多余字段）。

4. **取 N 的来源**：`MinimumPayService` 去重 + 清理改为从 `userService.getById(notifyConfig.getUserId()).getNotifyDedupMinutes()` 取全局 N（null→60）。

5. **新增接口** `NotifyDedupController`（或加到已有 controller）：
   - `GET /api/notify/dedup-minutes` → 返回当前用户全局 N。
   - `PUT /api/notify/dedup-minutes` body `{minutes: N}` → 更新当前用户 N（校验 ≥1）。

6. **`pageByUser`**：`recentMinutes` 改为**后端自行读当前用户全局 N**，前端不再传 recentMinutes（移除该 DTO 字段或保留忽略）。后端在 pageByUser 取 `userService.getByCurrentRequest().getNotifyDedupMinutes()` 作为过滤 N。

7. **前端**：
   - `MonitorConfigView`：**移除**「去重/过期分钟」输入框及相关 form 字段。
   - `NotifyHistoryView`：顶部加 N 输入框 + 保存按钮，调 GET/PUT 接口；页面加载时拉取 N 显示；查询不再前端传 recentMinutes（后端自动按全局 N 过滤）。

### 兼容
- 旧 ext_config 的 dedupMinutes 字段被忽略，无副作用。
- 生产 ALTER 加列有默认值，不影响现有行。


- 后端 `mvn -o compile` 通过。
- 前端 `npm run type-check` + `build` 通过。
- 生产实测：配置 N=5 → 等一次 cron 执行（或手动触发）→ 查 `store_pushed_history` 中该 config 超过 5 分钟的记录已删除；记录页只显示最近 5 分钟记录。
