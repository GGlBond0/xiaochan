# 执行计划

## 仓库与部署

- 后端：本仓库 `C:\D\AI\Projects\xiaocan\xiaocan-main`，origin `GGlBond0/xiaocan`。
- 前端：独立仓库 `C:\D\AI\Projects\xiaocan\xiaocan-front-main`，origin `GGlBond0/xiaocan-front`。
- 构建：本地 `mvn clean package -DskipTests`（JDK17/Maven 绝对路径，见 [[local-build-toolchain]]）；不在服务器跑 mvn。
- 部署：后端 scp jar → `systemctl restart xiaocan`；前端 `npm run build` → scp dist → `/var/www/xiaocan/dist`（见 [[deploy-topology]]）。
- DDL：生产手动执行。

## 执行步骤（有序）

### 阶段1：后端

- [ ] 1.1 `ddl.sql` 追加 `grab_config` 5 个新字段 DDL。
- [ ] 1.2 `GrabConfigEntity` 增 `auto/storeName/promoDetail/startTime/endTime` 字段。
- [ ] 1.3 `GrabConfigVO` 增同名字段。
- [ ] 1.4 `GrabConfigDTO` 增 `storeName/promoDetail/startTime/endTime`（手动建任务带快照）。
- [ ] 1.5 `AutoGrabServiceImpl` 改造：
  - 立即抢分支：save 占位(auto=1, 快照) → 置 lastResult="执行中"(防重) → 异步线程池 doGrab("AUTO") → 回调更新 lastGrabTime/lastResult/status。
  - 定时抢分支：save(auto=0, 快照) → refresh()。
  - 防重改为：当天 auto=1 且 lastGrabTime IS NULL 且 lastResult IS NULL。
- [ ] 1.6 `AutoGrabServiceImpl` 增独立 `ExecutorService`（`Executors.newCachedThreadPool`，独立于 taskScheduler）+ `@PreDestroy` shutdown。
- [ ] 1.7 `GrabServiceImpl.listByUserId` 加 `.ne(GrabConfigEntity::getAuto, true)` 过滤 + VO 透传（BeanUtils 已覆盖）。
- [ ] 1.8 **验证项**：grep `XiaochanHttp` 是否有共享可变字段；若有则在并发 doGrab 前处理（每线程 new 或同步）。
- [ ] 1.9 本地 `mvn clean package -DskipTests` 编译通过。

### 阶段2：前端

- [ ] 2.1 `GrabConfigView.vue` 表格增「商家名」「优惠」「时段」三列（活动ID 后）。时段列：row 缺 startTime/endTime 或为全天 → 显示「全天」tag，否则 `startTime-endTime`。
- [ ] 2.2 `form` 增 storeName/promoDetail/startTime/endTime；`pickStore` 从 row 带入。
- [ ] 2.3 `handleSubmit` payload 带快照字段。
- [ ] 2.4 `npm run build`（含 vue-tsc）通过。

### 阶段3：部署与验证

- [ ] 3.1 生产执行 DDL。
- [ ] 3.2 scp 后端 jar → 备份旧 jar → 替换 → `systemctl restart xiaocan` → 看 error.log 无启动异常。
- [ ] 3.3 前端 build → scp dist → 解包 → chown。
- [ ] 3.4 可选：执行僵尸清理 SQL（design.md 末尾）。
- [ ] 3.5 浏览器自动化验证（browser-relay / HTTP API 兜底，见 [[browser-relay-setup]]）：
  - 抢单页列表显示新三列。
  - 触发一条 autoGrab 监控命中（时段内），SSH 看 info.log 出现 `triggerType=AUTO` 的 doGrab，无"executeAt 已过期"。
  - 同活动二次命中被防重跳过，但不再永久挡。
- [ ] 3.6 SSH 看日志确认 AC1-AC6。

## 验证命令

```bash
# 后端编译（本地，绝对路径见 memory）
mvn clean package -DskipTests
# 前端构建
cd C:/D/AI/Projects/xiaocan/xiaocan-front-main && npm run build
# 生产日志
ssh root@121.91.175.192 'tail -200 /opt/xiaocan/logs/info.log | grep -E "AUTO|doGrab|自动抢单"'
```

## Review Gate

- 阶段1 编译通过后，自查 doGrab 异步回调的占位更新是否覆盖"成功/失败/异常"三态。
- 阶段3 部署后必须 SSH 看日志验证 AC1，否则不视为完成。

## 回滚点

- 后端 jar：部署前 `cp xiaocan.jar xiaocan.jar.bak.<ts>`；回滚 `cp bak jar && systemctl restart`。
- 前端 dist：`cp -r dist dist.bak.<ts>`；回滚 `rm -rf dist && mv dist.bak dist`。
- DDL 回滚：DROP 新增列。
