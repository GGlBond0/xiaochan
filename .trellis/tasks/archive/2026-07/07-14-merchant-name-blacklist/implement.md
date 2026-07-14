# 商家名称关键字黑名单 — 执行计划

按顺序执行。每个阶段有验证命令与回滚点。

## Phase A — 后端数据层与配置接口

A1. ddl.sql 末尾追加 `merchant_blacklist_config` 建表段（见 design.md 数据模型）。
A2. 新建 `MerchantBlacklistEntity`（model/entity），对齐 ProxyConfigEntity：`@TableName`、`@TableId(AUTO)`、`@TableLogic deleted`、`enabled`(Boolean)、`keywords`(String)、时间列。
A3. 新建 `MerchantBlacklistMapper`（mapper）：`extends BaseMapper<MerchantBlacklistEntity>`，`@Mapper`。
A4. 新建 `MerchantBlacklistDTO`（model/dto）：`enabled`、`keywords`（keywords 可空，限最大长度 4000）。
A5. 新建 `MerchantBlacklistVO`（model/vo）：`enabled`、`keywords`。
A6. 新建 `MerchantBlacklistService`（service 接口）：`getConfig()`、`updateConfig(DTO)`、`getEntity()`。
A7. 新建 `MerchantBlacklistServiceImpl`：`ensureRow()`(synchronized 懒初始化) + `getConfig`/`updateConfig`/`getEntity`；updateConfig 末尾调 `MerchantBlacklistHolder.invalidate()`。对齐 ProxyConfigServiceImpl。
A8. 新建 `MerchantBlacklistController`（controller）：`@RequestMapping("/api/blacklist")`，`GET /config`、`PUT /config`，两端点先 `userService.getByCurrentRequest()` 鉴权。
A9. 新建 `MerchantBlacklistHolder`（静态工具类，对齐 ProxyHolder）：`isBlacklisted(String)`、`invalidate()`、`loadCfg()`（5s 快照、异常回退"不过滤"），内部含规则解析（AND/OR、大小写不敏感、子串）。

**验证**：本机 `mvn -q compile`（见 [[local-build-toolchain]]）通过，无编译错误。

## Phase B — 后端过滤插入

B1. `StoreTask.filterStoreInfos`（StoreTask.java:111）：该方法 if/else 两分支各自返回独立 stream。在方法体最顶部、分支之前对入参做一次过滤：`storeInfos = storeInfos.stream().filter(s -> !MerchantBlacklistHolder.isBlacklisted(s.getName())).toList();`，单点覆盖 STORE_ACTIVITY 与 STORE_KEYWORD 两分支。
B2. `MinimumPayService.filterStoreInfos`（MinimumPayService.java:52）：同样在 stream 首部追加同一 filter。
B3. `GrabServiceImpl.doGrab`（GrabServiceImpl.java:410 拿到 storeName 后、:418 重试循环前）：插入 `if (MerchantBlacklistHolder.isBlacklisted(storeName)) { 记历史(黑名单拦截) + pushService 推通知 + return; }`。复用 doGrab 既有 history 写入与 PushService 注入，不新增方法签名。

**验证**：`mvn -q compile` 通过。人工通读三处插入点确认语义：null storeName 走原逻辑、enabled=false 不过滤。

## Phase C — 前端设置页

C1. `xiaocan-front-main/src/views/SettingsView.vue`：在现有"代理设置" `.settings-card` 之后新增第二个 `.settings-card` "商家黑名单"。
C2. 新增 `reactive` 表单 `blacklistForm = { enabled, keywords }` + `formRules`（无强制必填，keywords 可空）+ 独立 `loadBlacklist()` / `handleSaveBlacklist()`。
C3. `loadBlacklist`：`api.get('/api/blacklist/config')` 回填 `response.data.data`；`handleSaveBlacklist`：`formRef.validate()` → `api.put('/api/blacklist/config', {...})` → `ElMessage.success('保存成功，配置已即时生效')` → reload。
C4. 模板：启用开关 `el-switch`、关键字 `el-input type="textarea"`（多行，placeholder 提示规则：一行一条，`&` 表示同时包含，多行为任一命中）、保存/重读按钮。
C5. `onMounted` 复用既有 `await authState?.waitForAuth()` 后调 `loadBlacklist()`（与现有 loadConfig 并列）。

**验证**：`cd xiaocan-front-main && npm run build` 通过（见 [[frontend-deploy-dist-absolute-path]]：dist 部署用绝对路径打包）。

## Phase D — 部署

D1. 生产 MySQL 执行 ddl.sql 新段建表 `merchant_blacklist_config`。
D2. 后端本机 `mvn -q package` 打包，上传 jar 到生产、重启服务（[[prod-build-avoid-server]]：不在生产跑 mvn）。
D3. 前端 `npm run build`（绝对路径打包），部署 dist。
D4. 登录前端设置页，保存一条黑名单规则，确认生效。

**验证**：设置页可读可写；在某监控周期验证命中关键字门店不再推送；手动抢单命中黑名单商家时不发请求、记历史、推通知。

## 回滚点
- 任意 Phase 失败：还原该 Phase 涉及文件即可，无跨阶段强耦合。
- 生产回滚：删 ddl 段/表、回退 jar 与 dist、还原三处插入点。

## Review Gate
- 启动任务前（task.py start）需用户确认 prd/design/implement 三份产物。
- Phase B 完成后自检：三处插入点是否语义正确、null/disabled 不过滤。
- Phase C 完成后自检：前端区块与代理区块模式一致、鉴权 onMounted 顺序正确。
