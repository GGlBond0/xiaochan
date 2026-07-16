# 执行计划：地址管理登录态改为选择已有

## 顺序

### 后端（`C:\D\AI\Projects\xiaocan\xiaocan-main`）

1. [ ] `LoginStateService.java`：新增 `void bindLocation(Integer id, Long locationId);` 接口方法（含注释）。
2. [ ] `LoginStateServiceImpl.java`：实现 `bindLocation`：
   - `getByIdAndOwner(id)` 取并校验登录态归属。
   - locationId 非空时 `locationService.getById` + userId 校验（参照 `save` :208-214）。
   - patch 实体只 set id + locationId，`loginStateMapper.updateById`。
   - 当前用户 id 取法与 `save` 一致。
3. [ ] `LoginStateController.java`：新增 `@PutMapping("/{id}/location")`，`@RequestParam(required=false) Long locationId`，调 service。
4. [ ] 编译验证：本地 mvn 编译（见 [[local-build-toolchain]]，PATH 无 mvn，用绝对路径）。
   - `mvn -q -DskipTests compile` 通过。

### 前端（`C:\D\AI\Projects\xiaocan\xiaocan-front-main`）

5. [ ] `LocationView.vue`：
   - 表单状态 `loginForm` 改为 `{ selectedId: null }`，删 `name/rawHeaders`、`loginEditingId`。
   - 删 `openEditLogin`、`saveLoginState`；加 `availableLoginStates(locId)`、`bindLogin`、`unbindLogin`。
   - 模板 `:598-614` 对话框换为 el-select 下拉（见 design A）。
   - 模板 `:486-489` 登录态行"更新"按钮换为"解绑"（见 design C）。
   - 保留 `deleteLogin`、`loadLoginStates`、`loginStatesOf`、`loginStatusType`。
6. [ ] 前端构建：`npm run build` 通过（打包用绝对路径，见 [[frontend-deploy-dist-absolute-path]]）。

### 验证

7. [ ] 后端部署到生产（见 [[deploy-topology]]，本地构建，勿在服务器跑 mvn；jar 上传见 [[scp-large-jar-hangs-server]]）。
8. [ ] 浏览器自动化验证（[[browser-relay-setup]]，先 curl 127.0.0.1:18795/api/debug 验活）：
   - 地址页"+ 绑定登录态"出现下拉，仅含未绑定登录态。
   - 选一条→保存→出现在该地址列表、从其它地址下拉消失。
   - "解绑"→回到未绑定池、可被其它地址选择。
   - 明文未被改动（抢单/卡券仍可用该登录态）。

## 验证命令

- 后端编译：本地 mvn 绝对路径 `-q -DskipTests compile`
- 前端构建：`npm run build`
- relay 验活：`curl http://127.0.0.1:18795/api/debug`

## Review 门

- 后端编译通过 + 前端 build 通过 → 才进入部署验证。
- 浏览器实测三项绑定/解绑行为全通过 → 才标记完成。

## 回滚点

- 后端 service/controller 新增方法为纯增量，可直接 revert。
- 前端单文件 revert。
