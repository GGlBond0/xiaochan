# Implement: MonitoryConfig 事务 + afterCommit

## 执行清单

1. 编辑 `MonitoryConfigServiceImpl.java`：
   - 加 import：`org.springframework.transaction.annotation.Transactional`、`org.springframework.transaction.support.TransactionSynchronizationManager`、`org.springframework.transaction.support.TransactionSynchronization`。
   - 新增 private helper `afterCommit(Runnable)`（见 design）。
   - 5 方法加 `@Transactional(rollbackFor = Exception.class)`。
   - scheduler 调用改为 `afterCommit(() -> ...)`：
     - addUpdateConfig:178
     - updateConfig:188
     - deleteById:197
     - deleteByLocationId:206（改为先 list 取 ids、remove、afterCommit cancel）
     - toggleStatus:218

2. 本地编译：
   ```bash
   export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
   export PATH="$JAVA_HOME/bin:$PATH"
   "/c/D/tools/apache-maven-3.9.16/bin/mvn.cmd" -o -DskipTests clean compile 2>&1 | tail -10
   ```
   期望 BUILD SUCCESS。

3. （与 XiaochanHttp NPE 修复合并）构建 jar + 部署。

## 验证（部署后生产实测）

- 新增一个监控配置 → 日志 `已注册 cron 调度任务 configId: X`，DB 有记录，scheduler 一致。
- 修改配置（改 cron）→ 日志 `已取消` + `已注册`（refresh 内 cancel+reschedule），新 cron 生效。
- 删除配置 → 日志 `已取消 cron 调度任务 configId: X`，DB 无记录。
- 切换 status ENABLE↔DISABLE → scheduler 正确 register/cancel。
- deleteByLocationId（删地址触发）→ 该地址下所有配置 scheduler 取消、DB 删除。
- 事务回滚场景难造，但正常路径一致即合格。

## 回滚点

- 单文件 `git revert` MonitoryConfigServiceImpl.java 恢复无事务。
- 无 DB 变更。

## 风险文件

- `MonitoryConfigServiceImpl.java`：唯一改动。注意 afterCommit helper 的 import 完整、5 方法都加注解、scheduler 调用都包进 afterCommit。
