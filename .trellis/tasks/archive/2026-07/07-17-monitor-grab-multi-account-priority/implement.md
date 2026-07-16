# 执行计划：监控抢单多账号多平台优先级轮询

任务目录：`.trellis/tasks/07-17-monitor-grab-multi-account-priority`
规划文档：`prd.md`（需求/验收）、`design.md`（技术设计 §1–§9）。

## 执行顺序（按依赖）

### Step 0 — DDL（数据库先行，后端实体才能落字段）

- `ddl.sql` 追加：
  ```sql
  ALTER TABLE monitor_config ADD COLUMN grab_login_state_ids VARCHAR(255) NULL COMMENT '有序抢单账号id串,顺序=优先级;空回退grab_login_state_id';
  ALTER TABLE monitor_config ADD COLUMN grab_mode VARCHAR(16) NULL DEFAULT 'SINGLE' COMMENT '抢单模式 SINGLE/ALL';
  ALTER TABLE grab_config ADD COLUMN monitor_config_id INT NULL COMMENT '监控自动抢来源monitor_config.id;手动/定时为空';
  ALTER TABLE grab_config ADD COLUMN grab_seq VARCHAR(64) NULL COMMENT '降级游标 平台索引:账号索引';
  ALTER TABLE grab_config ADD COLUMN combo_snapshot TEXT NULL COMMENT '同门店所有组合快照JSON,供到点/降级重建组合';
  ```
- 生产服务器执行 SQL（见 [[deploy-topology]]、[[verify-deploy-claims]]）：`ssh root@121.91.175.192` 上 MySQL 执行（**不跑 mvn**，见 [[prod-build-avoid-server]]）。
- 读侧兜底：`grab_login_state_ids` 空 → 用 `grab_login_state_id` 单值；`grab_mode` 空 → SINGLE。

验证：SSH 进 MySQL `DESC monitor_config` / `DESC grab_config` 见新列。

### Step 1 — 后端实体/DTO/VO 字段

- `MonitorConfigEntity` 加 `grabLoginStateIds`(String) / `grabMode`(String)，保留 `grabLoginStateId`。
- `monitorConfigDTO` 加 `grabLoginStateIds` / `grabMode`，保留 `grabLoginStateId`。
- `NotifyConfigVO` 加 `grabLoginStateIds` / `grabMode`。
- `GrabConfigEntity` 加 `monitorConfigId`(Integer) / `grabSeq`(String)。
- 新增枚举 `GrabModeEnums { SINGLE, ALL }`（或直接用 String，倾向枚举强类型）。

验证：本地绝对路径编译（[[local-build-toolchain]]）`mvn -q -o compile` 通过。

### Step 2 — 后端保存校验（MonitoryConfigServiceImpl.addUpdateConfig）

- `autoGrab=true`：校验 `grabLoginStateIds` 非空，split 后每个 id `loginStateService.getEntity` 存在且属当前用户；同时 `grabLoginStateId` 置为列表第一个（兼容旧读路径）；`grabMode` 空默认 SINGLE。
- `autoGrab=false`：`grabLoginStateIds`/`grabLoginStateId`/`grabMode` 一律置 null。
- `grabPlatforms` 仍按逗号串存（顺序即优先级），非空校验保留（至少一个平台）。

验证：编译通过；接口保存/编辑往返（curl 或前端）正常，存量配置（无新字段）保存不报错。

### Step 3 — AutoGrabService 签名 + BaseTask 分组改造（design §5）

- `AutoGrabService.tryCreateFromMonitor` 签名：`(MonitorConfigEntity config, List<StoreInfo> sameStoreCombos)`。
- `BaseTask.triggerAutoGrab` 改：按 `storeId` 分组 `availableStores`，每组调 `tryCreateFromMonitor(config, 该组list)`；不同 storeId 组间独立。
- 保留 try/catch 吞异常 + warn（现有风格）。

验证：编译通过；监控命中多门店时日志见"按门店分组"调用。

### Step 4 — AutoGrabServiceImpl 优先级/换号/降级核心逻辑（design §4.1–§4.3, §6）

- 新增辅助：
  - `parseAccountIds(config)`：`grab_login_state_ids` 空 → `[grab_login_state_id]`；否则 split 保序。
  - `parsePlatformOrder(config)`：`grabPlatforms` 顺序即优先级；空 → `[1]`。
  - `filterValidAccounts(accounts, userId)`：过滤过期/不存在，保序。
- §4.2 立即抢内存循环（grabExecutor 内）：
  - 同门店组合按平台优先级排序 → 逐组合遍历（降级）。
  - 组合未到 start：若为当前最高可等待组合 → 落定时任务（executeAt=start, loginStateId=accounts[0], monitor_config_id, grab_seq="平台idx:0"），return；否则跳过。
  - 组合已过期：continue 下一组合。
  - 组合内按账号优先级循环 doGrab：成功→停；code==70/饭票不足/登录态过期→换下一账号；code==6/详情缺失/黑名单/位置无效/未知→break 账号循环降级下一组合。
  - 全部组合耗尽 → push 失败通知（含尝试门店/平台/账号概要）。
- §4.3 到点回调：`GrabCronScheduler.execute` 对带 `monitor_config_id` 的 auto=0 任务，从 monitor_config 重读账号/平台优先级/模式 + `grab_seq` 游标，转入 §4.2 从游标继续；成功→DISABLE；降级→可能落新定时任务。
- 防重键调整（design §3.3）：占位防重加入 `loginStateId` → `(userId,promotionId,loginStateId,当天,auto=1,lastGrabTime IS NULL)`。

验证：编译通过；本地无上游可联调，靠日志+单元/接口测路径覆盖（Step 9 集成验证）。

### Step 5 — ALL 模式分支（design §4.4）

- ALL：对 `filterValidAccounts` 列表**并行**（grabExecutor 多 submit）每个账号跑"门店遍历 + 门店内组合降级"，单账号在某门店**不换号**：
  - 单账号遍历各命中门店；每门店内按平台优先级组合降级，每组合只用自己试。
  - code==70/饭票不足/登录态过期 → 该账号放弃该门店，转下一门店（不在同门店内降级组合，因同门店该账号被限频则全组合皆限频）。
  - code==6/详情缺失/黑名单/位置无效 → 该门店该组合失败，降级该门店下一组合。
- SINGLE：单账号循环（Step 4）即含换号；ALL：单账号固定不换号。抽取公共"组合遍历降级"方法，差异仅在"账号循环 vs 单账号"。

验证：编译通过。

### Step 6 — doGrab 不改契约（design §R11）

- `GrabServiceImpl.doGrab` 对外契约不变；本次不动其内部重试逻辑（仍单账号单 config）。
- 多账号轮换在 AutoGrab 层通过多次构造 GrabConfigEntity + 多次调 doGrab 实现。
- 仅确认 doGrab 对 `storePlatform`/`loginStateId` 的读取与新字段无冲突（新字段 `monitorConfigId`/`grabSeq` doGrab 不读）。

验证：编译通过；手动抢/定时抢（无 monitor_config_id）行为不变。

### Step 7 — 前端（xiaocan-front-main/MonitorConfigView.vue，design §6）

- `form.grabLoginStateId`(单) → `form.grabLoginStateIds`(number[])；账号控件 el-select 单选 → **可排序多选**：`el-select multiple` + 选中后用上下移动按钮调顺序（D3 定：el-select multiple + 上下移按钮，简单可控，平台3个账号若干个都适用）。
- 平台控件 el-checkbox-group → 可排序多选：3 个平台用"选中 + 上下移"或拖拽 list；保存 `join(',')`（顺序即优先级，现有 line 378 join 已天然保序，复用）。
- 新增抢单模式 radio（SINGLE/ALL），autoGrab 开时显示，默认 SINGLE。
- 保存：`grabLoginStateIds.join(',')`、`grabPlatforms.join(',')`、`grabMode`；autoGrab=false 置 null 不提交。
- 加载反显（line 353-360 附近）：split 还原 `grabLoginStateIds` 顺序；`grabMode` 反显。
- 详情展示（line 615-616, 756）：账号多名字（按优先级）、平台按优先级、模式。
- 校验（line 176-180）：autoGrab=true 时 `grabLoginStateIds` 非空。

验证：前端本地 `npm run dev` 打开监控配置页，多选账号/平台可排序、保存往返反显正确；`npm run build` 用绝对路径打包（[[frontend-deploy-dist-absolute-path]]）成功。

### Step 8 — 部署

- 后端：本地编译出 jar（[[local-build-toolchain]]），**不 scp 整 jar（[[scp-large-jar-hangs-server]]，改 rsync 或分片）** 或走 GitHub Actions（[[backend-proxy-and-build]]）；服务器 systemd 重启（[[deploy-topology]]）。
- 前端：绝对路径打包 dist，部署到服务器前端目录。
- DDL：Step 0 已在生产 MySQL 执行。

验证：SSH 读 `/opt/xiaocan/logs`（[[runtime-logs-on-server]]）启动无报错；`curl` 接口正常。

### Step 9 — 集成验证（browser-relay，[[browser-relay-setup]]）

- 先 `curl http://127.0.0.1:18795/api/debug` 验活（`connected:true`）。会话无 browser_* 原生工具则用 HTTP API 兜底（[[browser-relay-mcp-status]]）。
- AC1–AC4：前端配置页多选/排序/模式保存往返。
- AC5：存量配置加载不报错、行为一致。
- AC6–AC11：构造命中（需活动在时段内/未开始两种），看日志优先级/等待/换号/降级（code==70 换号、code==6 降级、未到 start 建定时）。
- AC12–AC13a：ALL 模式多账号并行、单账号不换号、跨门店各抢。
- AC14–AC15：autoGrab=false 零抢单；手动/定时抢不变。

## Review Gate

- Step 1–2 完成 → review 实体/校验。
- Step 4–5 完成（核心逻辑）→ review 优先级/换号/降级/ALL 逻辑正确性（最易错，重点）。
- Step 7 完成 → review 前端可排序多选可用性。
- Step 9 → 全 AC 通过方可结题。

## Rollback

- DDL 列保留无副作用（读侧兜底）；后端回滚 jar；前端隐藏多选/模式控件即退化为单账号单平台（旧行为）；grab_config 新字段对旧逻辑为 null 不影响。

## 备注

- 本地编译/打包绝对路径见 [[local-build-toolchain]]、[[local-toolchain-inventory]]。
- 部署拓扑/服务名以 [[deploy-topology]] 实测为准，勿照任务文档臆造（[[verify-deploy-claims]]）。
- D4（code==70 本地短期标记）在 Step 4 实现时评估，非必需可不做。
