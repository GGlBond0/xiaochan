# Component Guidelines

> 组件约定。

---

## Overview

全部使用 `<script setup lang="ts">`，**无 Options API**。组件间不通过 props 通信——`NavBar` 不接收 props，views 之间通过路由跳转 + provide/inject 共享认证状态。

---

## Component Structure

`.vue` 文件内固定顺序：`<script setup lang="ts">` → `<template>` → `<style>`。

```vue
<script setup lang="ts">
import { ref, reactive, onMounted, inject } from 'vue'
import api from '../api'
// ...逻辑
</script>

<template>
  <!-- Element Plus 组件 -->
</template>

<style lang="scss" scoped>
$primary: #...;
/* 样式 */
</style>
```

例外：`HomeView.vue` 末尾额外一个非 scoped 的 `<style lang="scss">` 块（用于 `el-dialog` append-to-body 样式穿透，见 `HomeView.vue:1933`）。

---

## Props Conventions

- **当前不使用 props**：`defineProps`/`withDefaults`/`defineEmits` 在 `src/` 无匹配。组件间通过 provide/inject + 路由通信。
- 若新增可复用组件需要 props，用 `defineProps<{...}>()` + TS 泛型（遵循 `<script setup>` 习惯）。

---

## Styling Patterns

- **SCSS + scoped**：views 用 `<style lang="scss" scoped>`（`HomeView.vue`、`MonitorConfigView.vue`、`LocationView.vue`、`NotifyHistoryView.vue`）。
- `App.vue`/`NavBar.vue` 用 `<style scoped>`（无 lang）。
- SCSS 变量定义在各组件 scoped 块顶部（如 `HomeView.vue` 的 `$primary`），**无全局 SCSS 变量文件**。
- 全局样式：`src/styles/global.scss`。
- Element Plus：`main.ts` 全量注册 `app.use(ElementPlus)`，组件直接用 `el-card`/`el-dialog`/`el-form`/`el-table`；图标按需 `import { ArrowDown } from '@element-plus/icons-vue'`。

---

## Accessibility

- 未建立 a11y 标准（既有现状）。Element Plus 组件自带基础 a11y，本任务不强制新增。

---

## Common Mistakes

- 不要用 Options API——统一 `<script setup lang="ts">`。
- 样式穿透 `el-dialog`/`el-select` 弹层（append-to-body）需用非 scoped style 块或 `:deep()`，见 `HomeView.vue` 末尾非 scoped 块。
- Element Plus 组件已全量注册，无需在各组件局部注册。

---

## Convention: 登录态"选择已有"下拉（统一池引用）

登录态明文（抓包 header）只在 `/login-state` 管理页录入，其它业务页一律**下拉选择已有登录态**，不再各自粘贴 header。

**统一加载**：`api.get('/api/login-state/list')` → `loginStateList`（VO 字段 `userVayne` 非 `xcUserId`，含 `expireStatus`/`locationId`/`locationName`）。

**表单内下拉**（参照 `GrabConfigView.vue`）：
```html
<el-form-item label="登录态" prop="loginStateId">
  <el-select v-model="form.loginStateId" placeholder="..." style="width:100%">
    <el-option v-for="s in loginStateList" :key="s.id"
      :label="`${s.name}（用户${s.userVayne}${s.expireStatus==='已过期'?'/已过期':''}）`" :value="s.id" />
  </el-select>
  <div class="hint" v-if="loginStateList.length===0">未录入登录态，请先到「登录态管理」页面新增</div>
</el-form-item>
```

**地址页绑定/解绑**（`LocationView.vue`，2026-07-16 task 07-16-addr-login-state-select）：
- 地址卡片"+ 绑定登录态"对话框是 `el-select`（非 textarea 粘贴 header）。
- `availableLoginStates(locId)` = `loginStateList.filter(s => s.locationId==null || String(s.locationId)===String(locId))`——只列未绑定到其它地址的（一条登录态只能绑一个地址，`location_id` 单值）。
- 绑定调 `api.put('/api/login-state/'+id+'/location', null, {params:{locationId}})`；解绑调 `api.put('/api/login-state/'+id+'/location')`（不带 locationId）。后端只改 `location_id`，不动明文。
- 已绑该地址的登录态行提供"解绑"按钮（移回未绑定池，不删登录态）。
