# 执行计划：登录态统一

阶段化提交，每阶段独立可回滚。本地构建（JDK17+Maven，见 memory local-build-toolchain）。

## 阶段1：后端建表 + 迁移 + 统一 service/controller（不改旧代码读取路径）

- [ ] 1.1 `ddl.sql` 末尾追加：`DROP TABLE IF EXISTS login_state` + 建表 DDL（见 design）。
- [ ] 1.2 迁移 SQL：抢单行 `INSERT INTO login_state (id,user_id,name,sivir,session_id,user_vayne,silk_id,nami,location_id,expire_at,create_time,update_time) SELECT id,user_id,name,xc_sivir,xc_session_id,xc_user_id,silk_id,xc_nami,location_id,expire_at,create_time,update_time FROM grab_login_state`；霸王餐行 `INSERT INTO login_state (user_id,name,sivir,session_id,user_vayne,silk_id,city_code,raw_headers,create_time,update_time) SELECT user_id,name,sivir,session_id,user_vayne,silk_id,city_code,raw_headers,create_time,update_time FROM lottery_auth`（新分配 id）。附回滚注释。
- [ ] 1.3 新建 `LoginStateEntity` / `LoginStateMapper`。
- [ ] 1.4 新建 `LoginStateService` + `Impl`：合并 `parseRawHeaders`（含 X-Sivir/X-Session-Id/X-Nami/X-Vayne/X-Teemo/x-City/body.silk_id/JWT exp+userId），CRUD（save/list/delete/get），`toGrabAuth(id)`，`toLotteryAuth(id)`。
- [ ] 1.5 新建 `LoginStateController`：`POST/GET/DELETE /api/login-state`、`GET /api/login-state/list`、`GET /api/login-state/{id}`。
- [ ] 1.6 旧 `GrabController`/`LotteryController` 登录态接口暂不动（阶段2/3处理）。
- [ ] 1.7 本地 `mvn -q compile` 通过。
- [ ] **Gate G1**：编译过；迁移 SQL 在本地测试库跑通，`SELECT count(*)` 核对两表行数 = login_state 行数；抽查 `raw_headers` 不丢。

## 阶段2：业务读新表 + 前端切统一页/选择框

- [ ] 2.1 `GrabServiceImpl`：`doGrab`/`countTicketByAuth`/`listCards`/`countCards` 改用 `loginStateService.toGrabAuth(loginStateId)`，删除 `GrabAuth.from(entity)` 内联。
- [ ] 2.2 `LotteryServiceImpl.runTask` 改用 `loginStateService.toLotteryAuth(authId)`；`saveAuth/listAuth/deleteAuth` 委托 `LoginStateService`（保留旧接口路径，前端将切走）。
- [ ] 2.3 `GrabJwtExpireTask` 改扫 `login_state`。
- [ ] 2.4 旧 `GrabController` 登录态三接口委托 `LoginStateService`（保证前端未切完时仍可用）。
- [ ] 2.5 后端 `mvn -q compile` 通过。
- [ ] **Gate G2**：后端编译过；抢单/卡券/霸王餐接口冒烟（用已有配置 + 已有登录态）行为不变。
- [ ] 2.6 前端新增 `LoginStateView.vue` + 路由 `/login-state` + 导航入口。
- [ ] 2.7 前端 `GrabConfigView.vue` 下拉切 `/api/login-state/list`，VO 字段适配（`xcUserId`→`userVayne` 等）。
- [ ] 2.8 前端 `GrabCardView.vue` 登录态选择切同一接口。
- [ ] 2.9 前端 `SettingsView.vue` 霸王餐段：下线录入弹窗/列表，刷任务改"选已有登录态"下拉 + 运行；引导录入到 `/login-state`。
- [ ] 2.10 前端 `LocationView.vue` 抢单登录态段：管理主入口迁 `/login-state`，地址卡内仅显示绑定 + 解绑/换绑下拉。
- [ ] 2.11 前端打包通过（绝对路径打包，见 memory frontend-deploy-dist-absolute-path；如仅本地验证用 `npm run build`）。
- [ ] **Gate G3**：前端打包过；手动点查：登录态页录入一条 → 抢单配置下拉能选到 → 手动抢单一次成功 → 卡券查询能查 → 霸王餐选该登录态刷任务成功。

## 阶段3：清理旧表/旧接口（验证通过后单次提交）

- [ ] 3.1 删 `LotteryAuthEntity`/`LotteryAuthMapper`/`GrabLoginStateEntity`/`GrabLoginStateMapper`（确认无引用）。
- [ ] 3.2 删 `GrabController`/`LotteryController` 旧登录态接口；删 `GrabAuth.from(GrabLoginStateEntity)`。
- [ ] 3.3 `ddl.sql` 加 `DROP TABLE grab_login_state, lottery_auth`（附回滚注释），或保留表只删代码。
- [ ] 3.4 `mvn -q compile` + 前端 build 通过。
- [ ] **Gate G4**：全量构建过；最终回归冒烟。

## 验证命令

- 后端编译：`mvn -q -DskipTests compile`（本机 JDK17+Maven）
- 前端构建：`npm run build`（前端仓库目录）
- 迁移核对（测试库）：跑迁移 SQL 后 `SELECT (SELECT COUNT(*) FROM grab_login_state)+(SELECT COUNT(*) FROM lottery_auth) AS old_total, (SELECT COUNT(*) FROM login_state) AS new_total;` → 两数相等（deleted=0 过滤后）。

## 回滚点

- 阶段1回滚：删新表新代码（旧路径未动，无影响）。
- 阶段2回滚：git revert 阶段2提交，业务读回旧表。
- 阶段3回滚：git revert 阶段3提交，恢复旧表旧接口。
