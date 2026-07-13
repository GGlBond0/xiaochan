# 执行计划：首页3km筛选与距离排序及监控3km条件

## 执行顺序与检查点

### 后端（xiaochan-main）

- [ ] B1. `QueryListVO.java`：新增 `within3km` 字段（Boolean，注释）；`orderType` 注释补充 `4:距离排序`。
- [ ] B2. `XiaoChanServiceImpl.java`：
  - 新增常量 `DISTANCE_3KM = 3000`。
  - `query()` 路由：`orderType != 1` → `orderType != 1 || Boolean.TRUE.equals(queryListVO.getWithin3km())`。
  - `sortStoreList`：新增 `orderType == 4` 按距离升序（nullsLast）。
  - `filter`：末尾追加 within3km 过滤。
  - search 分支：追加对 `searchList` 结果应用 within3km + 距离排序（orderType=4）的一致性处理（抽公共 filter/sort 复用，避免重复代码）。
- [ ] B3. `MinimumPayExtNotifyConfig.java`：新增 `within3km = false`。
- [ ] B4. `StoreKeywordExtNotifyConfig.java`：新增 `within3km = false`（与 `limitDistance` 并存，注释说明）。
- [ ] B5. `MinimumPayService.filterStoreInfos`：追加 within3km filter。
- [ ] B6. `StoreTask.filterStoreInfos` 的 STORE_KEYWORD 分支：追加 within3km filter。
- [ ] B7. 后端编译验证：`mvn -q -DskipTests compile`（或在 xiaochan-main 目录执行项目构建命令）。

### 前端（xiaocan-front-main）

- [ ] F1. `HomeView.vue`：
  - `searchForm` 新增 `within3km: false`。
  - 排序区新增「距离」chip → `handleSort(4)`。
  - 顶部新增「3km 内」开关，change 触发 `handleSearch()`。
- [ ] F2. `MonitorConfigView.vue`：
  - `form.minimumPayExtNotifyConfig` / `form.storeKeywordExtNotifyConfig` 新增 `within3km: false`。
  - 表单 UI：MINIMUM_PAY/STORE_KEYWORD 下渲染「仅 3km 内」checkbox；STORE_ACTIVITY 不渲染。
  - 详情视图展示 within3km。
- [ ] F3. 前端构建验证：`npm run build`（xiaocan-front-main），确认产物无 TS/Vue 编译错误。

### 验证（gate）

- [ ] V1. 启动后端本地或部署后，`POST /api/xiaochan/query` 带 `within3km:true` → 返回门店全部 distance≤3000。
- [ ] V2. `orderType:4` → 列表按 distance 升序，null 排末尾。
- [ ] V3. `within3km:true` + `orderType:4` 组合正常。
- [ ] V4. `within3km:false`（或不传）→ 行为与改动前一致（回归）。
- [ ] V5. 监控配置提交 MINIMUM_PAY/STORE_KEYWORD 带 `within3km:true`，任务命中门店全部 distance≤3000。
- [ ] V6. STORE_ACTIVITY 表单无 3km 选项；历史配置（无 within3km）行为不变。

### 部署（按 memory 拓扑，详见 [[deploy-topology]] 记忆）

- [x] D1. 后端 push fork `GGlBond0/xiaocan`（注意：是 xiaocan 不是 xiaochan）→ GitHub Actions `Build Production JAR with Config`（`workflow_dispatch`，必填 `mysql_password`）→ 下载 artifact `xiaocan-prod-17` → scp `/opt/xiaocan/xiaocan.jar`（属主 xiaocan:xiaocan，644）→ `systemctl restart xiaocan`。已实测部署成功，2026-07-14。
- [ ] D2. 前端 fork `GGlBond0/xiaocan-front`：⚠️ 该 fork 当前**无任何 GitHub Actions workflows**，"Build Only → /var/www/xiaocan/dist" 未经验证、属臆造。前端实际构建/部署方式待探明（需 ssh 探测服务器静态目录 + nginx 配置 + 前端本地 `npm run build` 产物如何上传），勿照此步骤执行。
- [ ] D3. 浏览器访问 `http://121.91.175.192:8088/` 验证首页 + 监控配置页。（8088 端口/nginx 路径待实测确认）

## 回滚点
- 后端：JAR 回滚到 `/opt/xiaocan/xiaocan.jar.bak.<YYYYMMDD-HHMMSS>`（实测命名规范，非 `/opt/xiaocan.old.*`）；`systemctl restart xiaocan`。
- 前端：待探明后再补充回滚点。
- 代码层：新增字段均为可选，回滚前端不影响后端；后端回滚到旧 JAR，前端新字段被忽略（旧 QueryListVO 反序列化忽略未知字段，安全）。

## Review Gates
- G1：B2 后（query 路由 + filter/sort）自测通过再继续 B3-B6。
- G2：B7 编译通过再动前端。
- G3：V1-V6 全绿后再部署。
