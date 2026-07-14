# 执行计划：代理 IP 池配置可视化设置页

> 验收措订正：因 `GlobalResultExceptionHandler` 将所有 `BusinessException` 统一映射为 HTTP 200 + `BaseResult.error(msg)`，`prd.md` 验收点 2 的"未登录返回 401"实际表现为 **HTTP 200 + `success=false` + "无效token" 消息**（前端拦截器已按 `code !== 200` 弹错）。实现不变。

## 执行顺序（先后端，后前端）

后端必须先就绪接口，前端设置页才有可调用目标。两仓库独立构建部署。

---

## A. 后端（xiaocan-main）

### A1. DDL：追加 `proxy_config` 表
- 文件：`ddl.sql`
- 在文件末尾追加建表语句：
  ```sql
  DROP TABLE IF EXISTS `proxy_config`;
  CREATE TABLE `proxy_config` (
    `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID，固定1（全局单份）',
    `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用代理',
    `api_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '代理API地址',
    `ttl` int NOT NULL DEFAULT 28 COMMENT '代理缓存有效期(秒)',
    `retry` int NOT NULL DEFAULT 3 COMMENT '失败换代理重试次数',
    `request_timeout` int NOT NULL DEFAULT 5000 COMMENT '上游请求超时(毫秒)',
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` tinyint(1) NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`id`) USING BTREE
  ) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '代理IP池全局配置表' ROW_FORMAT = Dynamic;
  ```
- 验证：`grep -c proxy_config ddl.sql` ≥ 命中建表段。

### A2. Entity / DTO / VO
- `model/entity/ProxyConfigEntity.java`：仿 `LocationEntity`，`@TableName("proxy_config")`，`@TableId(type=IdType.AUTO) Integer id`、`Boolean enabled`、`String apiUrl`、`Integer ttl`、`Integer retry`、`Integer requestTimeout`、`LocalDateTime createTime/updateTime`、`@TableLogic Boolean deleted`。
- `model/dto/ProxyConfigDTO.java`：字段 `Boolean enabled`、`String apiUrl`、`@Min(1) Integer ttl`、`@Min(1) Integer retry`、`@Min(1000) Integer requestTimeout`，`@Data`。（apiUrl 条件必填在 service 校验。）
- `model/vo/ProxyConfigVO.java`：同 DTO 字段（驼峰），`@Data`。

### A3. Mapper / Service / ServiceImpl
- `mapper/ProxyConfigMapper.java`：`@Mapper public interface ProxyConfigMapper extends BaseMapper<ProxyConfigEntity> {}`
- `service/ProxyConfigService.java`：
  - `ProxyConfigVO getConfig();`
  - `void updateConfig(ProxyConfigDTO dto);`
  - `ProxyConfigEntity getEntity();`  // 供 ProxyHolder 调，永不 null
- `service/impl/ProxyConfigServiceImpl.java extends ServiceImpl<ProxyConfigMapper, ProxyConfigEntity>`：
  - 私有 `ProxyConfigEntity ensureRow()`：`synchronized`，`getById(1)`，为空则用 Environment 读 `PROXY_ENABLED/PROXY_API_URL/PROXY_TTL/PROXY_RETRY/PROXY_REQUEST_TIMEOUT` 默认值构造 entity，`save`，二次 `getById(1)` 返回。
  - `getEntity()`：`return ensureRow();`
  - `getConfig()`：`BeanUtils.copyProperties(ensureRow(), vo)` 返回 VO。
  - `updateConfig(dto)`：校验 `Boolean.TRUE.equals(enabled)` 时 `apiUrl` 非空否则抛 `BusinessException("启用代理时 API 地址必填")`；`ensureRow()` 取现有行拷贝字段后 `updateById`（id=1）；调用 `ProxyHolder.invalidate();`。
  - Environment 读取复用 `SpringContextUtil.getApplicationContext().getEnvironment().getProperty(key)`，抽私有 `env(key, def)`。

### A4. Controller
- `controller/ProxyConfigController.java`：`@RestController @RequestMapping("/api/proxy")`，注入 `ProxyConfigService proxyConfigService`、`UserService userService`。
  - `@GetMapping("/config") BaseResult<ProxyConfigVO> get()`：`userService.getByCurrentRequest();` → `return BaseResult.ok(proxyConfigService.getConfig());`
  - `@PutMapping("/config") BaseResult<Void> update(@Valid @RequestBody ProxyConfigDTO dto)`：`userService.getByCurrentRequest();` → `proxyConfigService.updateConfig(dto);` → `return BaseResult.ok();`

### A5. 改造 `ProxyHolder`
- 文件：`http/ProxyHolder.java`
- 新增静态字段：`volatile ProxyConfigEntity cfgSnapshot`、`volatile long cfgLoadedAt`、`static final long CFG_TTL = 5000L`。
- 新增 `private static ProxyConfigEntity loadCfg()`：
  - `synchronized`：若 `cfgSnapshot != null && now - cfgLoadedAt < CFG_TTL` 返回快照。
  - 否则 `try { service = SpringContextUtil.getBean(ProxyConfigService.class); entity = service.getEntity(); if(entity != null){ cfgSnapshot=entity; cfgLoadedAt=now; return entity; } } catch (Exception e) { log.warn } `
  - 兜底：返回 `null` 或一个由 `env()` 填的临时默认 entity。设计为：catch 到异常时回退到旧 `env(key,def)` 路径，构造临时 `ProxyConfigEntity` 返回（不缓存）。
- 改 `enabled()`：`ProxyConfigEntity c = loadCfg(); return c != null ? Boolean.TRUE.equals(c.getEnabled()) : env("PROXY_ENABLED","false").equalsIgnoreCase("true");`
- 改 `retry()` / `requestTimeout()`：同理优先 `loadCfg()`，兜底 `env()` + `Integer.parseInt`。
- 改 `getProxy(force)`：`ttl` 取 `loadCfg().getTtl()`（兜底 28），`fetchProxy()` 内 `apiUrl` 取 `loadCfg().getApiUrl()`（兜底 `env("PROXY_API_URL","")`）。
- 改 `invalidate()`：在原有清 `cachedProxy/cachedAt` 基础上，`cfgSnapshot = null; cfgLoadedAt = 0;`（让下次重读 DB，即时生效）。
- 保留 `env(key,def)` 私有方法作为兜底。
- `fetchProxy` 原逻辑（fastjson2 解析 `code==0` / `data[0].IP/Port`）不变。

### A6. 构建验证（本机）
- `cd C:\D\AI\Projects\xiaocan\xiaocan-main` → `mvn -q -DskipTests compile`（本机 JDK17+Maven 已装，见 memory `local-build-toolchain`）。
- 修复编译错误直至通过。
- 不在生产服务器跑 mvn。

### A7. 接口手测（可选，需本机起服务）
- 起 MySQL，执行新 DDL 段；启动服务。
- 不带 token `GET /api/proxy/config` → 期望 `success=false` + "无效token"。
- 带 token `GET` → 返回 5 项配置（首启为环境变量默认值）。
- 带 token `PUT` 改 `enabled=true` → 再 `GET` 回显 → 观察日志"获取代理"用新值。

**审查门 A**：A1–A6 完成且本机编译通过，进入前端。

---

## B. 前端（xiaocan-front-main）

### B1. 路由
- `src/router/index.ts`：在 routes 末尾加 `{ path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue') }`。

### B2. 导航
- `src/components/NavBar.vue`：在"通知记录"`router-link` 之后加：
  ```html
  <router-link to="/settings" class="nav-link" :class="{ active: currentPage === 'settings' }">设置</router-link>
  ```

### B3. 设置页 `src/views/SettingsView.vue`
- 仿 `MonitorConfigView.vue` / `HomeView.vue` 风格，element-plus form。
- `<script setup>`：注入 `authState`、`useRouter`、`api`。
- `form = reactive({ enabled:false, apiUrl:'', ttl:28, retry:3, requestTimeout:5000 })`，`formRef`、`saving`、`loading`。
- `formRules`：
  - `apiUrl`：validator，`form.enabled===true` 且空时报"启用代理时 API 地址必填"。
  - `ttl`/`retry`：`@Min(1)` 式校验（`type:'number', min:1`）。
  - `requestTimeout`：`min:1000`。
- `onMounted`：`await authState.waitForAuth()` → `api.get('/api/proxy/config')` → 成功则 `Object.assign(form, data)` 回填。
- `handleSave()`：`formRef.validate()` → `api.put('/api/proxy/config', form)` → 成功 `ElMessage.success('保存成功')` + 重新 `loadConfig()` 回显。
- `<template>`：标题"代理设置" + `el-form`，`el-switch`(enabled)、`el-input`(apiUrl)、`el-input-number`(ttl/retry/requestTimeout，带单位后缀文字)、保存按钮。样式照搬 HomeView 的卡片/SCSS 变量风格（`$primary` 等），scoped。

### B4. 构建验证
- `cd C:\D\AI\Projects\xiaocan\xiaocan-front-main` → `npm run build`（或 `npm run build-only` 视 package.json scripts）。
- 修复 TS/构建错误直至通过。

**审查门 B**：B1–B4 完成，构建通过。

---

## C. 集成验收

1. 执行 `ddl.sql` 新段建表（生产/测试库）。
2. 后端 jar 部署（本机 mvn 打包，上传，遵循 memory `prod-build-avoid-server`）。
3. 前端 `npm run build` 后部署 `dist`。
4. 浏览器登录 → 顶部见"设置"入口 → 进入表单回显当前配置 → 改值保存 → 提示成功并回显 → 后端日志确认新配置生效 → 门店查询/抢单功能正常。

## 回滚点

- 后端：还原 `ProxyHolder.java`、删除 `ProxyConfig*` 四类 + Controller；`DROP TABLE proxy_config`；撤 `ddl.sql` 追加段。
- 前端：删 `SettingsView.vue`、撤 router/NavBar 改动。

## 验证命令清单

```bash
# 后端编译
cd C:\D\AI\Projects\xiaocan\xiaocan-main && mvn -q -DskipTests compile
# 前端构建
cd C:\D\AI\Projects\xiaocan\xiaocan-front-main && npm run build
```
