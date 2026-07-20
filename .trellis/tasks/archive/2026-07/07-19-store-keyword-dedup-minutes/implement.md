# Implement: STORE_KEYWORD 分钟级去重与过期清理

## 执行清单（有序）

1. 编辑 `src/main/java/io/github/xiaocan/tasks/StoreTask.java`：
   - 加 import：`UserService`、`UserEntity`、`Set`、`Collectors`、`MonitorTypeEnums`（按需，已有则跳过）。
   - 注入 `@Resource private UserService userService;`
   - 新增私有方法 `dedupMinutesOf(MonitorConfigEntity)` 与 `dedupKey(Integer, Integer)`，照搬 `MinimumPayService.java:40-44` 与 `78-80`。
   - 重写 `cleanupExpired(MonitorConfigEntity)`：开头 `if (notifyConfig.getType() != MonitorTypeEnums.STORE_KEYWORD) return;`，其余照搬 `MinimumPayService.java:83-93`。
   - 改 `filterStoreInfos` 的 STORE_KEYWORD 分支（`StoreTask.java:127-141`）：保留黑名单/库存/距离过滤；在 stream 前用 `findPushedWithinMinutes(notifyConfig.getId(), dedupMinutesOf(notifyConfig))` 取 `pushed` 集合（key=`storeId+promotionId`）；stream 内去重改为 `!pushed.contains(dedupKey(...))`。移除对 `findByNotifyIdAndStoreIdAll` 的调用。

2. 本地编译：
   ```bash
   # 用绝对路径 JDK17/Maven（见 auto-memory [[local-build-toolchain]]）
   mvn -o compile
   ```
   期望：BUILD SUCCESS。绝不跑在生产服务器上。

3. grep 确认 `findByNotifyIdAndStoreIdAll` 无其它调用方后保留死代码：
   ```bash
   grep -rn "findByNotifyIdAndStoreIdAll" src/main/java
   ```
   预期仅剩 `StorePushedHistoryService(Impl)` 定义处，无业务调用。

4. 构建产物 jar（GitHub Actions 或本地，见 [[prod-build-avoid-server]]）。

5. 部署到生产（路径/步骤见 auto-memory [[deploy-topology]]，勿照任务文档臆造）。

## 验证（部署后生产实测）

- 等一次 configId=5 的 cron 执行（0:00–0:30 每分钟，或手动触发一次监控）。
- SSH 读日志：
  - 期望命中时出现 `发送消息:...斑斓包点...`（BaseTask:254）。
  - 当有过期记录时出现 `configId: 5 清理 N 分钟前的过期推送记录 X 条`。
  - 不应再出现因永久去重导致的「没有满足条件的门店活动」除非确实无库存/距离不符/全在 N 分钟内推过。
- 验证同店不同 promotionId：白天关键词命中同店多活动场景下，新 promotionId 不被旧 promotionId 阻挡（需自然命中，不强造）。
- 验证 STORE_ACTIVITY 既有行为：观察 configId（STORE_ACTIVITY 类）仍走 `checkRepeat` 当天去重，日志不应出现其被 N 分钟清理（因 `cleanupExpired` 开头 type 判断已挡）。

## 回滚点

- 单文件：`git revert` `StoreTask.java` 改动即可恢复永久去重。无 DB 变更。
- 已删的 configId=5 历史（排查时手动 DELETE 的 2 条）不影响回滚——回滚后变回永久去重，但已无旧记录，下次命中会再推一次然后又被永久屏蔽（符合回滚后的旧行为）。

## 风险文件

- `StoreTask.java`：唯一改动文件。注意 `cleanupExpired` 的 type 判断不能漏，否则 STORE_ACTIVITY 当天记录会被误清。
- 不触 `MinimumPayService.java`、`BaseTask.java`、`StorePushedHistoryServiceImpl.java`、前端。
