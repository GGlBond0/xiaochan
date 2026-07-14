# 技术设计：登录态统一

## 现状关键事实

- 两表存同一种东西（小蚕 App 账号登录态），字段名不同：
  - `grab_login_state`: `xc_sivir` `xc_session_id` `xc_user_id`(=Vayne) `xc_nami` `silk_id` `expire_at` `location_id`
  - `lottery_auth`: `sivir` `session_id` `user_vayne` `silk_id` `city_code` `raw_headers`
- 抓包解析逻辑两处近似重复：`GrabServiceImpl.java:80-190`、`LotteryServiceImpl.java:80-162`，共用正则 `HEADER_LINE`。差异：抢单解析 X-Nami + JWT exp/UserId；霸王餐解析 x-City + body.silk_id 兜底。
- 引用点：
  - `grab_config.login_state_id` → 抢单（`GrabServiceImpl.doGrab` `countTicketByAuth` `listCards` `countCards`）
  - 霸王餐 `runTask(authId)` 直接查 `lottery_auth`
  - 卡券查询 `listCards/countCards` 传 `loginStateId` 查 `grab_login_state`
  - `GrabJwtExpireTask` 扫 `grab_login_state` 全表
- 前端：抢单登录态管理嵌在 `LocationView` 地址卡内；霸王餐登录态录入在 `SettingsView:151+`；抢单配置下拉在 `GrabConfigView:372-378`。

## 合并表设计

新表 `login_state`（统一字段命名，去掉 `xc_` 前缀）：

```sql
CREATE TABLE `login_state` (
  `id`          INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`     INT NOT NULL COMMENT '系统用户ID',
  `name`        VARCHAR(64) NOT NULL COMMENT '别名',
  `sivir`       VARCHAR(800) NULL DEFAULT NULL COMMENT 'X-Sivir JWT',
  `session_id`  VARCHAR(64) NULL DEFAULT NULL COMMENT 'X-Session-Id',
  `user_vayne`  INT NULL DEFAULT NULL COMMENT '小蚕用户id(X-Vayne/JWT.UserId)',
  `silk_id`     INT NULL DEFAULT 0 COMMENT 'silk_id(请求体+X-Teemo)',
  `nami`        VARCHAR(32) NULL DEFAULT NULL COMMENT 'X-Nami(可选,默认随机)',
  `city_code`   INT NULL DEFAULT NULL COMMENT 'x-City 城市码(霸王餐用,可空)',
  `location_id` BIGINT NULL DEFAULT NULL COMMENT '所属地址id(抢单可选绑定,老记录/霸王餐留空)',
  `expire_at`   DATETIME NULL DEFAULT NULL COMMENT 'JWT过期时间(解析exp)',
  `raw_headers` TEXT NULL DEFAULT NULL COMMENT '录入的原始抓包header(留底)',
  `create_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_location_id` (`location_id`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='小蚕App账号登录态统一池';
```

设计要点：
- 所有"业务专属"字段（`nami`/`city_code`/`location_id`/`expire_at`/`raw_headers`）都设可空，任一业务都可不全填。
- `sivir` 取 800（抢单表较大值），避免霸王餐 JWT 迁入截断。
- 不引入 `type` 枚举列——同一登录态可被多业务共用，靠 `location_id` 是否空 + 各业务下拉是否按地址筛选来区分用途，不强行分类。

## 字段映射 / 迁移

迁移 SQL（追加到 `ddl.sql` 末尾，幂等：`DROP TABLE IF EXISTS login_state` 后建表 + `INSERT ... SELECT`）：

| login_state 字段 | 来自 grab_login_state | 来自 lottery_auth |
|---|---|---|
| sivir | xc_sivir | sivir |
| session_id | xc_session_id | session_id |
| user_vayne | xc_user_id | user_vayne |
| silk_id | silk_id | silk_id |
| nami | xc_nami | NULL |
| city_code | NULL | city_code |
| location_id | location_id | NULL |
| expire_at | expire_at | (运行时按 sivir 解析补，或留空由定时任务补) |
| raw_headers | NULL（旧表无留底，不补） | raw_headers |

- 迁移后 `grab_config.login_state_id` 仍指向旧行 id → 必须做 **id 平移**：迁移时保证 `grab_login_state` 的旧行按原 id 插入 `login_state`（`INSERT INTO login_state (id, ...) SELECT id, ... FROM grab_login_state`），`lottery_auth` 行 id 接续（用 `id + 偏移` 或让 lottery 行新分配 id，但 lottery 行无外部引用，可重分）。这样 `grab_config.login_state_id` 无需改值即可继续指向新表同 id。
- `lottery_auth` 行：当前无 `grab_config` 引用，刷任务只按 `authId` 临时传参（前端选完即用），id 变化无影响 → 直接新分配 id 迁入。
- 迁移保留旧表（不 DROP），回滚 = 改代码读回旧表。

## 后端改造

### 新增
- `LoginStateEntity`（`@TableName("login_state")`，字段见上表）
- `LoginStateMapper extends BaseMapper`
- `LoginStateService` / `LoginStateServiceImpl`：统一 header 解析（合并两处正则逻辑为一个私有方法，同时解析 X-Sivir/X-Session-Id/X-Nami/X-Vayne/X-Teemo/x-City/body.silk_id/JWT exp），CRUD + 列表 + 按 id 取 `GrabAuth`/`LotteryAuth` 适配。
- 适配方法：
  - `GrabAuth LoginStateService.toGrabAuth(id)`：从新表读 → 构建 `GrabAuth`（sivir/session_id/userId/silkId/nami）。
  - `LotteryAuth LoginStateService.toLotteryAuth(id)`：从新表读 → 构建 `LotteryAuth`（silkId/userVayne/sessionId/sivir/cityCode）。
- `LoginStateController`：`/api/login-state`（POST 保存、GET list、DELETE {id}、GET {id}）。

### 改造（读新表）
- `GrabServiceImpl.doGrab` / `countTicketByAuth` / `listCards` / `countCards`：`grabLoginStateMapper.selectById(loginStateId)` → `loginStateService.toGrabAuth(loginStateId)`，删除内联 `GrabAuth.from(entity)`。
- `LotteryServiceImpl.runTask`：`lotteryAuthMapper.selectById(authId)` → `loginStateService.toLotteryAuth(authId)`；`saveAuth`/`listAuth`/`deleteAuth` 改为委托 `LoginStateService`（保留旧接口路径或前端切到新接口，见下）。
- `GrabJwtExpireTask`：扫 `login_state` 替代 `grab_login_state`。
- `GrabController` 登录态三接口：保留旧路径 `/api/grab/login-state*` 委托 `LoginStateService`（向后兼容，前端切完后可删），或直接前端统一切到 `/api/login-state`。**推荐**：旧路径委托新 service，前端切新接口，验证后下阶段删旧路径。

### 解析逻辑合并
抽取到 `LoginStateServiceImpl.parseRawHeaders(String raw)` 返回中间结构（含 sivir/sessionId/nami/vayne/teemo/cityCode/bodySilkId/exp）。抢单/霸王餐只读自己需要的字段。保持两业务原有校验差异（抢单要求 sivir+session 非空；霸王餐要求 silkId+session+sivir 非空）。

## 前端改造

- 新增 `LoginStateView.vue` + 路由 `/login-state`；导航加入口。
  - 列表：别名 / 账号(userVayne) / silk_id / 过期状态 / 绑定地址(显示名) / 更新时间。
  - 录入/编辑弹窗：name + rawHeaders 粘贴框（与现 `SettingsView` 霸王餐录入、`LocationView` 抢单录入同体验）。
  - 删除按钮。
  - 可选：按地址筛选下拉（抢单配置用）。
- `GrabConfigView.vue:372-378` 登录态下拉：接口从 `/api/grab/login-state/list` 切到 `/api/login-state/list`（VO 字段适配）。
- `GrabCardView.vue` 登录态选择：同上切接口。
- `SettingsView.vue:151-235` 霸王餐段：下线登录态录入弹窗与列表，刷任务改为"选择已有登录态"下拉 + 运行按钮；录入入口引导到 `/login-state`。
- `LocationView.vue` 抢单登录态段：保留绑定关系显示，但"新增/编辑"改为跳转 `/login-state`（或保留快捷录入委托新接口）；删除保留。具体：管理主入口迁到 `/login-state`，地址卡内仅显示绑定 + 解绑/换绑下拉。
- `api/index.ts`：无需改鉴权 token（`user.token` 不动）。

## 兼容 / 回滚

- 阶段1：建 `login_state` + 迁移数据 + 新 service/controller，旧代码仍读旧表（双写期：新接口写新表，旧接口暂不动）。
- 阶段2：改造各业务读新表 + 前端切新接口/新页；旧表保留。
- 阶段3：验证通过后，删旧接口、旧 mapper、旧 entity、旧表（单次提交，可回滚 = revert）。
- 回滚点：每个阶段独立提交；阶段2回滚只需切回旧表读取。

## 风险

- id 平移：`grab_config.login_state_id` 必须仍能命中 → 迁移脚本用 `INSERT ... SELECT id` 保证抢单行 id 不变。**关键验证点**。
- `expire_at`：`lottery_auth` 旧行无此字段，迁移时为空；首过 `GrabJwtExpireTask` 时按 `sivir` 解析补齐，或迁移脚本里就地解析补。
- `sivir` 长度：lottery 表 VARCHAR(512)，grab 表 800，统一 800。
- 前端三处下拉 VO 字段名变更（`xcUserId` → `userVayne` 等）需同步改。
