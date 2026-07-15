# Design: MINIMUM_PAY 去重键升级 + 批量优化

## 设计目标

1. 去重键从 `(notifyConfigId, storeId)` 升级为 `(notifyConfigId, storeId, promotionId)`，修复跨天新活动被昨日同店记录误挡。
2. 消除 filter 阶段的 N+1 查询。
3. 新增覆盖索引支撑批量查询与 cleanup 删除。

## 改动点

### 1. `StorePushedHistoryService` 新增批量查询方法

新增接口方法（替代逐店调 `findByNotifyIdAndStoreIdWithinMinutes`，该方法保留——可能仍被其它逻辑或测试引用，不删以免破坏 STORE_KEYWORD 等）：

```java
/**
 * 查询某监控配置在最近 minutes 分钟内已推送过的 (storeId, promotionId) 集合。
 * 用于 MINIMUM_PAY 批量去重：内存比对，消除逐店单查。
 */
Set<StorePushedKey> findPushedKeysWithinMinutes(Integer notifyId, int minutes);
```

`StorePushedKey` 为轻量载体（`storeId` + `promotionId`），或直接返回 `List<StorePushedHistoryEntity>` 由调用方取两字段——倾向后者，避免新增类型，但 `promotionId` 可为 null，集合元素需处理。

实现（`StorePushedHistoryServiceImpl`）：
```java
return lambdaQuery()
        .select(StorePushedHistoryEntity::getStoreId,
                StorePushedHistoryEntity::getPromotionId)
        .eq(StorePushedHistoryEntity::getNotifyConfigId, notifyId)
        .ge(StorePushedHistoryEntity::getCreateTime, LocalDateTime.now().minusMinutes(minutes))
        .list();
```
- `.select(...)` 限定列，配合覆盖索引走 index-only。
- 返回后由 `MinimumPayService` 组装为 `Set<String>`（key = `storeId + ":" + promotionId`）或 `Set<Pair>`，promotionId 为 null 时用占位（如 `"null"`），保证同 storeId 不同 promotionId 不冲突。

### 2. `MinimumPayService.filterStoreInfos` 改批量比对

```java
int dedupMin = dedupMinutesOf(notifyConfig);
Set<String> pushed = storePushedHistoryService.findPushedKeysWithinMinutes(notifyConfig.getId(), dedupMin)
        .stream()
        .map(e -> dedupKey(e.getStoreId(), e.getPromotionId()))
        .collect(Collectors.toSet());
return storeInfos
        .stream()
        .filter(s -> !MerchantBlacklistHolder.isBlacklisted(s.getName()))
        .filter(s -> s.getLeftNumber() > 0)
        .filter(s -> s.getPrice().subtract(s.getRebatePrice()).compareTo(extNotifyConfig.getMinimumPay()) <= 0)
        .filter(s -> !Boolean.TRUE.equals(extNotifyConfig.getWithin3km())
                || (s.getDistance() != null && s.getDistance() <= 3000))
        .filter(s -> !pushed.contains(dedupKey(s.getStoreId(), s.getPromotionId())))
        .toList();
```

`dedupKey(Integer storeId, Integer promotionId)` 工具：`storeId + ":" + (promotionId == null ? "null" : promotionId)`。

- `promotionId` 为 null 的活动（极端情况）单独成键，不与有 promotionId 的活动混淆。
- 行为变化点：同一 `(storeId, promotionId)` 在 dedupMin 内仍跳过；不同 promotionId 不再互相阻挡 → 修复目标 case。

### 3. DDL：覆盖索引

```sql
ALTER TABLE store_pushed_history
  ADD INDEX idx_notify_time_store_promo (notify_config_id, create_time, store_id, promotion_id);
```

- 列序 `(notify_config_id, create_time, store_id, promotion_id)`：等值(`notify_config_id`) + 范围(`create_time >= ?`) 前缀扫描，后两列覆盖返回，index-only。
- 同时服务 `cleanupExpired` 的删除 `WHERE notify_config_id=? AND create_time < ?`（前两列命中），与 `pageByUser` 的 `notify_config_id + create_time` 过滤。
- 旧单列索引 `idx_notify_config_id`/`idx_create_time` 保留（STORE_KEYWORD `findByNotifyIdAndStoreIdAll`、STORE_ACTIVITY 当天查询仍用）。

### 4. cleanupExpired 不改逻辑

`MinimumPayService.cleanupExpired` 仍调 `deleteByNotifyIdOlderThanMinutes(notifyConfig.getId(), dedupMin)`，删除 `WHERE notify_config_id=? AND create_time < now-dedupMin`，由新索引的前两列加速。语义、策略均不变。

## 不变量

- `user.notifyDedupMinutes` 仍由 `dedupMinutesOf` 读取，null 兜底 60，行为不变。
- `pageByUser` 不动。
- STORE_ACTIVITY / STORE_KEYWORD 调用的去重方法不动（仍用 `findByNotifyIdAndStoreIdAll` 等）。
- `findByNotifyIdAndStoreIdWithinMinutes` 暂保留（避免误删引用；若确认仅 MINIMUM_PAY 用，可在本任务内一并删，留 implement 阶段 grep 确认）。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 新索引增加写放大 | store_pushed_history 写量小（监控命中才写），可忽略 |
| `findByNotifyIdAndStoreIdWithinMinutes` 残留死代码 | implement 阶段 grep 确认调用点，仅 MINIMUM_PAY 用则删 |
| 批量查询在超大历史集下内存压力 | 已 `.select` 仅取两列 + 时间窗限定（默认 60min），单配置量极小 |
| promotionId 为 null 的键冲突 | dedupKey 显式占位 `"null"` |

## 上线步骤（按 deploy-topology 记忆，勿臆造）

1. DDL 在生产库执行（与现有部署一致的方式，具体连接/方式 implement 时 recall deploy-topology）。
2. 代码本地 JDK17 + Maven 绝对路径构建产出 jar，scp/rsync 到生产（注意 [[scp-large-jar-hangs-server]]，43MB jar 用 rsync 或分片，勿直接 scp）。
3. 重启 systemd 服务，读 `/opt/xiaocan/logs` 验证启动与去重日志。
4. 不在生产服务器跑 mvn。

## 验证

- 本地编译通过。
- 日志确认 filter 后查询数为 `1 + 1 + 1`。
- 用 23:40→00:00 场景人工构造（或等待自然跨天）观察 P2 被推送。
- `explain` 批量查询与 cleanup 删除走新索引。
