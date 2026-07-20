# Design: STORE_KEYWORD 分钟级去重与过期清理

## 架构与边界

只改后端 `StoreTask` 一个类，把 STORE_KEYWORD 分支接入 MINIMUM_PAY 已落地的「用户全局 N 分钟窗口去重 + 过期清理」机制。无 DB、无前端、无 service 新方法。

## 数据流

监控执行（`StoreTask` 经 `BaseTask.runSingle`）：
1. `cleanupExpired(notifyConfig)` → 取用户全局 N → `deleteByNotifyIdOlderThanMinutes(notifyConfig.id, N)`，清本配置 N 分钟前历史（无命中也清）。
2. `fetchStoreInfos` → `xiaoChanService.searchList(keyword, city, lng, lat)`（不变）。
3. `filterStoreInfos` STORE_KEYWORD 分支：
   - 黑名单过滤（不变）
   - `leftNumber > 0`（不变）
   - `limitDistance` / `within3km` 距离过滤（不变）
   - 去重改为：`findPushedWithinMinutes(notifyConfig.id, N)` 取窗口内已推送记录 → 组装 `storeId+promotionId` 集合 → 命中集合则跳过。
4. 命中后 `savePushedHistory` 写 `store_pushed_history`（不变）。
5. `sendMessage` 推送（不变）。

去重键 `storeId+promotionId`：同店同活动 N 分钟内不重复推；同店不同 promotionId 视为新活动照常推。

## 变更点（唯一文件：`src/main/java/io/github/xiaocan/tasks/StoreTask.java`）

1. **注入依赖**：新增 `@Resource UserService userService`（StoreTask 当前未注入 UserService）。`StorePushedHistoryService` 已注入，复用。

2. **新增 `dedupMinutesOf`**：与 `MinimumPayService.dedupMinutesOf` 同实现，取 `userService.getById(notifyConfig.getUserId()).getNotifyDedupMinutes()`，null→60。
   - 取舍：是否抽到 `BaseTask` 共用？——不抽。MINIMUM_PAY 已有自己的私有 `dedupMinutesOf`，本次再复制一份，保持两个子类独立、改动最小、不引入跨类耦合。若后续第三处再用可再上提。

3. **新增 `dedupKey`**：`storeId + ":" + (promotionId==null?"null":promotionId)`，与 `MinimumPayService.dedupKey` 同实现。

4. **改 `filterStoreInfos` STORE_KEYWORD 分支**（`StoreTask.java:127-141`）：保留黑名单/库存/距离过滤，去重段由
   ```java
   .filter(storeInfo -> storePushedHistoryService
           .findByNotifyIdAndStoreIdAll(notifyConfig.getId(), storeInfo.getStoreId()) == null)
   ```
   改为：
   ```java
   int dedupMin = dedupMinutesOf(notifyConfig);
   Set<String> pushed = storePushedHistoryService
           .findPushedWithinMinutes(notifyConfig.getId(), dedupMin)
           .stream()
           .map(e -> dedupKey(e.getStoreId(), e.getPromotionId()))
           .collect(Collectors.toSet());
   // ...在 stream 内
   .filter(storeInfo -> !pushed.contains(dedupKey(storeInfo.getStoreId(), storeInfo.getPromotionId())))
   ```
   注意 `findPushedWithinMinutes` 必须在 stream 构建前调用（一次性取集合），不能放进 `.filter` lambda 内（避免每元素都查库）。与 MinimumPayService 写法一致。

5. **重写 `cleanupExpired`**（当前继承空实现）：
   ```java
   @Override
   protected void cleanupExpired(MonitorConfigEntity notifyConfig) {
       try {
           int dedupMin = dedupMinutesOf(notifyConfig);
           int deleted = storePushedHistoryService
                   .deleteByNotifyIdOlderThanMinutes(notifyConfig.getId(), dedupMin);
           if (deleted > 0) {
               log.info("configId: {} 清理 {} 分钟前的过期推送记录 {} 条",
                       notifyConfig.getId(), dedupMin, deleted);
           }
       } catch (Exception e) {
           log.warn("configId: {} 清理过期推送记录失败", notifyConfig.getId(), e);
       }
   }
   ```
   STORE_ACTIVITY 分支是否也走这个清理？——**不走**。`cleanupExpired` 对 StoreTask 整体生效，但 STORE_ACTIVITY 的 `store_pushed_history` 由 `checkRepeat`（当天去重）控制、当天记录不应被 N 分钟清理误删。需让 `cleanupExpired` 仅对 STORE_KEYWORD 生效：
   - 方案：在 `cleanupExpired` 开头加 `if (notifyConfig.getType() != MonitorTypeEnums.STORE_KEYWORD) return;`。保证 STORE_ACTIVITY 路径行为不变。

6. **import 调整**：加 `UserService`、`UserEntity`、`Set`、`Collectors`、`MonitorTypeEnums`（按需）。

## 契约 / 兼容

- `findByNotifyIdAndStoreIdAll` 方法签名保留（R6），仅 StoreTask 不再调用。grep 确认无其它调用方后可保留为死代码（与 07-16 design 一致：保留不删）。
- 旧 STORE_KEYWORD 历史 `store_pushed_history` 记录（如 configId=5 的 2 条已在排查时手动删除）在 N 分钟外的，下次执行会被 `cleanupExpired` 自动清理，无需手动。
- 旧 ext_config 无 dedupMinutes 字段——本方案不读 ext_config 取 N，无影响。
- N 仍来自 `user.notify_dedup_minutes`，与 MINIMUM_PAY 共用同一全局值，用户在 NotifyHistoryView 调一次 N 即对两类监控同时生效。

## 风险与权衡

| 风险 | 缓解 |
|---|---|
| `cleanupExpired` 误清 STORE_ACTIVITY 当天记录 | 开头按 `type != STORE_KEYWORD` 提前 return |
| STORE_KEYWORD 历史记录积累已多，首次清理一次删多条 | 量小（关键词配置命中才写），单次删除无锁无性能问题；`log.info` 记录删除数可见 |
| `findPushedWithinMinutes` 写进 stream lambda 导致 N+1 | 严格在 stream 前一次性取集合（与 MinimumPayService 同写法） |
| 抽 `dedupMinutesOf` 到 BaseTask 的诱惑 | 不抽，保持本次改动最小、不触动 BaseTask / MinimumPayService |

## 回滚

单文件回滚 `StoreTask.java` 即恢复永久去重行为。无 DB 变更需回滚。
