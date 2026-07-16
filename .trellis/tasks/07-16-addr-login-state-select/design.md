# 设计：地址管理登录态改为选择已有

## 总体

后端补一个"只改 locationId"的接口；前端把地址页登录态绑定对话框从 textarea 改为下拉选择，并新增解绑操作。`login_state.location_id` 保持单值（一个登录态最多绑一个地址）。

## 后端改动

### 1. Service 接口 `LoginStateService`

新增方法：

```java
/**
 * 绑定/解绑登录态到地址。只改 location_id，不动登录态明文。
 * @param id 登录态 id（归属当前请求用户，无权抛 BusinessException）
 * @param locationId 地址 id；null 表示解绑。非空时必须归属当前用户。
 */
void bindLocation(Integer id, Long locationId);
```

### 2. Service 实现 `LoginStateServiceImpl.bindLocation`

实现要点（复用现有能力）：

```java
@Override
public void bindLocation(Integer id, Long locationId) {
    LoginStateEntity entity = getByIdAndOwner(id); // 复用：归属当前用户，无权抛异常
    Long target = null;
    if (locationId != null) {
        LocationEntity loc = locationService.getById(locationId);
        if (loc == null || !loc.getUserId().equals(/* 当前用户 */)) {
            throw new BusinessException("无权绑定该地址");
        }
        target = locationId;
    }
    // 只更新 location_id 一列，避免覆盖明文
    LoginStateEntity patch = new LoginStateEntity();
    patch.setId(id);
    patch.setLocationId(target);
    loginStateMapper.updateById(patch);
    log.info("登录态绑定地址 id={}, locationId={}", id, target);
}
```

说明：
- 复用 `getByIdAndOwner(id)` 做登录态归属校验（已有，`LoginStateService.java:47`）。
- 地址归属校验逻辑与现有 `save` 中 `:208-214` 一致。
- 用"只 set id + locationId 的 patch 实体 + updateById"，MyBatis-Plus 默认非 null 字段更新，**不会触碰 sivir/sessionId/rawHeaders 等列**。
- 当前用户 id 取法与 `save` 一致（`userService.getByCurrentRequest().getId()`）。

### 3. Controller `LoginStateController`

新增：

```java
/**
 * 绑定/解绑登录态到地址。只改所属地址，不改登录态明文。
 * @param id 登录态 id
 * @param locationId 地址 id；不传或传 null 表示解绑
 */
@PutMapping("/{id}/location")
public BaseResult<Void> bindLocation(@PathVariable Integer id,
                                     @RequestParam(required = false) Long locationId) {
    loginStateService.bindLocation(id, locationId);
    return BaseResult.ok();
}
```

约定：`locationId` 走 query 参数（`?locationId=`），不传=解绑。避免引入新 DTO。

### 接口契约

`PUT /api/login-state/{id}/location?locationId={long|null}`
- 200 `{code:0}` 成功；403/业务异常 无权。
- 前端调用：绑定 `api.put('/api/login-state/'+id+'/location', null, { params: { locationId } })`；解绑 `api.put('/api/login-state/'+id+'/location')`。

## 前端改动（`LocationView.vue`）

### A. 替换"绑定登录态"对话框

把 `:598-614` 的 textarea 对话框改为下拉选择对话框：

```html
<el-dialog v-model="loginDialogVisible" title="绑定登录态" width="480px" align-center>
  <el-form label-width="90px">
    <el-form-item label="登录态">
      <el-select v-model="loginForm.selectedId" placeholder="选择未绑定的登录态" style="width:100%" filterable>
        <el-option v-for="s in availableLoginStates(loginDialogLocationId)" :key="s.id"
          :label="`${s.name}（用户${s.userVayne}${s.expireStatus==='已过期'?'/已过期':''}）`" :value="s.id" />
      </el-select>
      <div class="hint" v-if="availableLoginStates(loginDialogLocationId).length===0">
        没有可绑定的登录态，请先到「登录态管理」页面录入
      </div>
    </el-form-item>
  </el-form>
  <template #footer>
    <el-button @click="loginDialogVisible=false">取消</el-button>
    <el-button type="primary" :loading="loginSaving" :disabled="!loginForm.selectedId" @click="bindLogin">绑定</el-button>
  </template>
</el-dialog>
```

- `availableLoginStates(currentLocationId)`：`allLoginStates.value.filter(s => s.locationId == null || String(s.locationId)===String(currentLocationId))`。
  - 即"未绑定"或"已绑当前地址"的都可选（已绑当前的显示为选中态，便于取消重选）。
- 表单状态改为 `reactive({ selectedId: null })`，移除 `name/rawHeaders`。

### B. 改写绑定逻辑

```ts
function openAddLogin(locationId: string) {
  loginDialogLocationId.value = locationId
  loginForm.selectedId = null
  loginDialogVisible.value = true
  if (allLoginStates.value.length === 0) loadLoginStates()
}

async function bindLogin() {
  if (!loginForm.selectedId) return
  loginSaving.value = true
  try {
    await api.put(`/api/login-state/${loginForm.selectedId}/location`, null,
      { params: { locationId: Number(loginDialogLocationId.value) } })
    ElMessage.success('已绑定')
    loginDialogVisible.value = false
    await loadLoginStates()
  } catch { /* 拦截器已提示 */ }
  finally { loginSaving.value = false }
}
```

### C. 已绑登录态行：把"更新"改为"解绑"

`:486-489` 改为：

```html
<div class="login-ops">
  <el-button size="small" link type="warning" @click="unbindLogin(s)">解绑</el-button>
  <el-button size="small" link type="danger" @click="deleteLogin(s)">删除</el-button>
</div>
```

```ts
async function unbindLogin(row: any) {
  try { await ElMessageBox.confirm(`确定把登录态「${row.name}」从该地址解绑？`, '提示', { type: 'warning' }) }
  catch { return }
  await api.put(`/api/login-state/${row.id}/location`)  // 不带 locationId = 解绑
  ElMessage.success('已解绑')
  await loadLoginStates()
}
```

### D. 移除的旧代码

- `openEditLogin`、`saveLoginState`（粘贴 header 那套）、`loginForm.rawHeaders/name`、`loginEditingId`、`rawHeaders` 对话框 UI、`:608` 提示。
- `loginStatusType` 仍保留（列表 tag 用）。

### 数据来源

- `allLoginStates` 已有（`loadLoginStates` → `/api/login-state/list`）。`LoginStateVO` 已含 `locationId`，足以判断"未绑定"。

## 兼容性 / 影响

- 后端新接口为纯增量，不改现有 `POST /api/login-state` 语义，不影响抢单/霸王餐/卡券。
- `location_id` 单值约束不变；一条登录态被绑到新地址即从旧地址移除（自然行为，下拉已隐藏已绑项）。
- `/login-state` 管理页的"绑定地址"下拉与本项目并存：两处都能改 location_id，语义一致，互不冲突。

## 回滚

- 后端：revert controller/service 新增方法（纯增量，安全）。
- 前端：revert `LocationView.vue`（单文件）。
