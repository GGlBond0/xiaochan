# 执行计划：监控配置自动建立抢单任务

## 实现顺序

### Step 1 DB schema
- [ ] 在 `ddl.sql` 末尾追加 `ALTER TABLE monitor_config ADD auto_grab ... , ADD grab_login_state_id ...`（见 design.md）。
- [ ] 同步在服务器 MySQL 执行（或纳入部署脚本，以 [[deploy-topology]] 为准）。

### Step 2 后端字段层
- [ ] `MonitorConfigEntity` 加 `autoGrab`(Boolean) / `grabLoginStateId`(Integer)，`@TableField` 与列名对齐。
- [ ] `monitorConfigDTO` 加同名字段。
- [ ] `NotifyConfigVO` 加同名字段供回显。
- [ ] `MonitoryConfigServiceImpl.addUpdateConfig` 转换处补字段拷贝；校验段加：`autoGrab=true` 时 `grabLoginStateId` 必填且属于当前用户。

### Step 3 AutoGrabService 核心
- [ ] 先读 `GrabConfigEntity` / `grab_config` DDL，确认防重用哪个日期字段（`create_time`? `execute_at`?），以及状态枚举类名。
- [ ] 读 `GrabHistoryMapper`/Service，确认起始记录写入方法签名。
- [ ] 读 `GrabJwtExpireTask` 推送通道，确认过期提醒复用方式。
- [ ] 新建 `AutoGrabService` 接口 + `AutoGrabServiceImpl.tryCreateFromMonitor(config, store)`，按 design.md 流程实现。
- [ ] `parseHHMM` 辅助 + 容错。
- [ ] `GrabConfigMapper` 加防重 exists 查询（LambdaQueryWrapper）。

### Step 4 触发点接入
- [ ] `StoreTask` 命中推送后，对每个 `StoreInfo` 调 `autoGrabService.tryCreateFromMonitor`，try/catch 吞异常 + warn。
- [ ] `MinimumPayService` 同样接入。
- [ ] 确认两执行体都能拿到当前 `MonitorConfigEntity` 本体（探查已确认持有）。

### Step 5 前端
- [ ] 前端独立仓库 `xiaocan-front-main`（[[frontend-repo-path]]），定位 `MonitorConfigView.vue`。
- [ ] 表单加「自动抢单」el-switch；勾选后出现「抢单账号」el-select（数据源 `/api/login-state`）。
- [ ] 保存/编辑 DTO 带新字段；校验勾选时账号必填。
- [ ] 列表卡片加「自动抢单」tag 回显。
- [ ] 重新构建 dist 用绝对路径打包（[[frontend-deploy-dist-absolute-path]]），勿打错源。

### Step 6 本地构建验证
- [ ] 本地 JDK17+Maven 绝对路径编译后端通过（[[local-build-toolchain]]）。
- [ ] 不在服务器跑 mvn（[[prod-build-avoid-server]]）。

## 验证命令

```bash
# 后端本地编译（绝对路径，PATH 不带 mvn）
# 路径见 [[local-toolchain-inventory]] / [[local-build-toolchain]]
```
（具体 mvn/java 绝对路径在实现期 recall 记忆后填入实际命令）

## 验收对照（见 prd.md Acceptance Criteria）

- 回归零影响：`autoGrab=false` 配置命中只通知、不建任务。
- 美团活动命中建任务、非美团不建。
- 同活动一天一次（重复命中跳过）。
- 时间分支：未到点/立即/过期 三态正确。
- 登录态过期跳过 + 提醒。

## Review Gates

- **Gate 1（Step 3 前）**：读完 `GrabConfigEntity`/`GrabHistoryMapper`/`GrabJwtExpireTask` 三个待确认项，把实际字段名/方法签名回写到 design.md「待实现期确认项」并勾掉。
- **Gate 2（Step 4 后）**：本地编译通过 + 用 browser-relay（[[browser-relay-setup]]）或 curl 接口实测一次监控命中→建任务链路，确认 `grab_config` 有新行、`grab_history` 有 AUTO_MONITOR 记录、`GrabCronScheduler` 注册成功。

## Rollback Points

- 前端：隐藏开关即可，后端 autoGrab 永远 false。
- 后端：触发点 try/catch 已隔离，移除两处调用即退回纯通知。
- DB：列保留无副作用，无需回滚。
