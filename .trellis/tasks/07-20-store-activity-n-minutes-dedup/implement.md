# Implement: STORE_ACTIVITY 去掉命中即停

## 执行清单

1. 编辑 `src/main/java/io/github/xiaocan/tasks/StoreTask.java` 的 `afterSuccess`（:168-175）：
   - 删除 `if (!availableStores.isEmpty() && notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) { monitoryConfigService.toggleStatus(...); }` 整块。
   - 保留 `super.afterSuccess(notifyConfig, availableStores);`（或删整个 override，super 为空实现——选择保留 override 调 super，减少 import 清理风险）。
2. grep 确认 `MonitorConfigStatusEnums` import 是否还被 StoreTask 其它地方用：
   ```bash
   grep -n "MonitorConfigStatusEnums" src/main/java/io/github/xiaocan/tasks/StoreTask.java
   ```
   若仅 afterSuccess 用，删 import；若 `disableIfLocationGone` 等其它方法也用（如 :173 之外），保留。核实 `execute`/`filterStoreInfos` 等是否引用。

3. 本地编译（绝对路径 JDK17/Maven，见 [[local-build-toolchain]]）：
   ```bash
   export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
   export PATH="$JAVA_HOME/bin:$PATH"
   "/c/D/tools/apache-maven-3.9.16/bin/mvn.cmd" -o -DskipTests clean compile 2>&1 | tail -10
   ```
   期望 BUILD SUCCESS。

4. 构建产物 jar（本地或 GitHub Actions，见 [[prod-build-avoid-server]]，勿在生产跑 mvn）。

5. 部署到生产（路径/步骤见 [[deploy-topology]]，勿照任务文档臆造）。

## 验证（部署后生产实测）

- 找一个 ENABLE 的 STORE_ACTIVITY 配置（或手动启用一个历史 DISABLE 的），等其 cron 命中推送一次。
- 推送后查 monitor_config 该配置 `status`：**应保持 ENABLE**（不再是 DISABLE）。
- 同天后续 cron：日志应出现 `configId: X 没有满足条件` 或 checkRepeat 跳过（当天不重复推），不应再出现第二次推送。
- 第二天（或手动模拟跨天）：该店有新活动（新 promotionId）时，应再次命中并推送。
- STORE_KEYWORD / MINIMUM_PAY 配置行为不受影响（日志对照）。

## 回滚点

- 单文件 `git revert` StoreTask.java 恢复「命中即停」。无 DB 变更。
- 已被本次改动「不再停用」的配置无需回滚处理；回滚后下次命中又会停（符合旧行为）。

## 风险文件

- `StoreTask.java`：唯一改动。注意只删 afterSuccess 的 DISABLE 块，勿动 checkRepeat / filterStoreInfos / cleanupExpired。
