# 技术设计：代理 IP 池配置可视化设置页

## 边界

- 后端：`xiaocan-main`（本仓库），Java 17 + Spring Boot 3 + MyBatis-Plus + fastjson2 + hutool。
- 前端：`xiaocan-front-main`（`C:\D\AI\Projects\xiaocan\xiaocan-front-main`），Vue 3 + Vite + TS + Element Plus + vue-router。
- 两个仓库独立 git，本次各改各的、各自构建部署。

## 数据模型

### 新表 `proxy_config`（全局单份，逻辑上只存一行）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int, PK, auto | 固定取 1（全局单份） |
| `enabled` | tinyint(1) | 是否启用代理，0/1 |
| `api_url` | varchar(500) | 拉取代理 IP 的上游 API 地址 |
| `ttl` | int | 代理缓存有效期秒数 |
| `retry` | int | 失败换代理重试次数 |
| `request_timeout` | int | 上游请求超时毫秒 |
| `update_time` | datetime | 最后修改时间 |
| `create_time` | datetime | 创建时间 |
| `deleted` | tinyint(1) | 逻辑删除标志（沿用全局 logic-delete） |

DDL 追加到 `ddl.sql`。`id` 用 `IdType.AUTO`，但业务约定只操作 id=1 的行。

### 实体 / DTO / VO

- `ProxyConfigEntity`（`model.entity`）：`@TableName("proxy_config")`，字段与表对应，`@TableLogic deleted`，沿用 `LocationEntity` 风格。
- `ProxyConfigDTO`（`model.dto`）：`PUT` 入参，含 `enabled / apiUrl / ttl / retry / requestTimeout`，用 jakarta validation 注解（`@Min`、`@NotBlank` 条件校验在 service 层做）。
- `ProxyConfigVO`（`model.vo`）：`GET` 返回，字段同 DTO（驼峰），用 `@Data` + builder 或直接 `BeanUtils.copyProperties`。

## 分层与契约

### Mapper / Service / ServiceImpl

- `ProxyConfigMapper extends BaseMapper<ProxyConfigEntity>`（`@Mapper`），无自定义方法（用 `LambdaQueryWrapper` / `getById` 即可）。
- `ProxyConfigService` 接口：
  - `ProxyConfigVO getConfig()` —— 读全局配置，表空则用环境变量默认值初始化并返回。
  - `void updateConfig(ProxyConfigDTO dto)` —— 更新全局配置，更新后调用 `ProxyHolder.invalidate()` 让缓存失效、下次重读。
  - `ProxyConfigEntity getEntity()` —— 供 `ProxyHolder` 内部读原始配置（避免循环依赖，返回 entity）。
- `ProxyConfigServiceImpl extends ServiceImpl<ProxyConfigMapper, ProxyConfigEntity>`：
  - `getConfig()`：`getById(1)`，为空则 `initRow()`（从 Environment 读 `PROXY_*` 默认值落库）再返回。
  - `updateConfig(dto)`：校验（enabled=true 时 apiUrl 非空；ttl/retry/requestTimeout ≥1），`saveOrUpdate` id=1，然后 `ProxyHolder.invalidate()`。
  - `getEntity()`：`getById(1)`，为空时也调 `initRow()`，保证 `ProxyHolder` 永不拿到 null（兜底用 Environment 默认值）。

### Controller

`ProxyConfigController`（`/api/proxy`）：
- `GET /config` → `BaseResult<ProxyConfigVO>`：内部先 `userService.getByCurrentRequest()`（未登录抛 401 BusinessException），再 `service.getConfig()`。
- `PUT /config` → `BaseResult<Void>`：`getByCurrentRequest()` + `@Valid @RequestBody ProxyConfigDTO` + `service.updateConfig(dto)`。

> 用 `userService.getByCurrentRequest()` 强制登录态，复用现有 401 语义（`UserServiceImpl` 抛 `BusinessException(401, ...)`）。`BaseResult` 无显式 code=401 路径，统一异常处理已有 `GlobalResultExceptionHandler`，由它转 401 响应（验证该 handler 行为）。

## ProxyHolder 改造（核心）

### 现状问题
现 `ProxyHolder` 用 `env(key, def)` 经 `SpringContextUtil.getApplicationContext().getEnvironment().getProperty(key)` 读环境变量。改为运行时读 DB 后，环境变量只作首启动兜底默认值。

### 改造方案
保留静态方法签名不变（`enabled() / retry() / requestTimeout() / getProxy(force) / invalidate()`），把"配置来源"从 Environment 切到 `ProxyConfigService.getEntity()`，并加一层**内存快照缓存**避免每次取代理都打 DB：

```
ProxyHolder:
  - private static volatile ProxyConfigEntity cfgSnapshot;   // 内存快照
  - private static volatile long cfgLoadedAt;
  - private static final long CFG_TTL = 5_000ms;              // 配置快照刷新间隔（短，兼顾即时性）
  - private static ProxyConfigEntity loadCfg():
      if snapshot 未过期 -> return snapshot
      service = SpringContextUtil.getBean(ProxyConfigService.class)
      entity = service.getEntity()   // 永不 null（service 兜底初始化）
      cfgSnapshot = entity; cfgLoadedAt = now
      return entity
  - enabled()  -> loadCfg().getEnabled()
  - retry()     -> loadCfg().getRetry()
  - requestTimeout() -> loadCfg().getRequestTimeout()
  - getProxy(force): ttl 用 loadCfg().getTtl()；apiUrl 用 loadCfg().getApiUrl()
  - invalidate(): 仍清代理缓存 cachedProxy/cachedAt；updateConfig 调它即触发重读
```

### 即时生效保证
- `updateConfig` 落库后立即 `ProxyHolder.invalidate()`（清代理缓存）。
- 同时为让配置快照也即时刷新，`invalidate()` 内顺手清 `cfgSnapshot`（置 null），使下次任何静态方法调用都重新 `loadCfg()` 读最新 DB 行。这样保存后下一次请求即用新值，无需等 5s 快照过期，也无需重启。

### 线程安全
- `loadCfg()` 用 `synchronized`（与现有 `getProxy` 一致），写 `cfgSnapshot` 用 volatile，读路径无锁（volatile 读）。
- `ProxyHolder.getProxy` 已是 `synchronized`，配置读取量小，synchronized 开销可忽略。

### 降级与兜底
- `loadCfg()` 若 `SpringContextUtil.getBean` 抛异常（极早期启动阶段）或 service 返回 null，回退到现有 `env(key, def)` 读 Environment 默认值，保证启动期不崩。
- `enabled()` 取值用 `Boolean.TRUE.equals(entity.getEnabled())`，避免 null 拆箱 NPE。

### 启动期初始化
- 不强制在启动时初始化表行（避免 Bean 循环依赖：`ProxyHolder` 静态类不应依赖 service 初始化顺序）。
- 首次 `getEntity()` 调用时若表空则落库一行默认值（惰性初始化）。或更稳妥：在 `ProxyConfigServiceImpl` 构造后用 `@PostConstruct` 探一次表，空则初始化。采用惰性方案，避免与 `ProxyHolder` 静态调用产生时序耦合。

## 前端设计

### 路由
`src/router/index.ts` 新增：
```ts
{ path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue') }
```

### 导航
`src/components/NavBar.vue` 在 `navbar-nav` 末尾加：
```html
<router-link to="/settings" class="nav-link" :class="{ active: currentPage === 'settings' }">设置</router-link>
```
放在"通知记录"之后（顶部最右），贴合"顶部添加设置页"。

### 设置页 `SettingsView.vue`
- 仿 `MonitorConfigView.vue` / `HomeView.vue` 风格（element-plus form + ElMessage）。
- `onMounted`：等 `authState.waitForAuth()` 后 `GET /api/proxy/config` 回填表单。
- 表单字段：
  - 启用代理：`el-switch`（enabled）
  - 代理 API 地址：`el-input`（apiUrl），enabled=true 时必填
  - 缓存有效期(秒)：`el-input-number` min=1
  - 失败重试次数：`el-input-number` min=1
  - 请求超时(毫秒)：`el-input-number` min=1000
- 保存按钮：`PUT /api/proxy/config`，成功 `ElMessage.success` + 重新拉取回显。
- 复用 `api.get/post`（`src/api/index.ts`），token 拦截器已自动带 header。

## 兼容性 / 回滚

- 旧 EnvironmentFile 仍可用作首启动默认值；若 DB 已有行则以 DB 为准（运行时优先）。
- 回滚：还原 `ProxyHolder`、删 `ProxyConfig*` 类与表、前端删路由/导航/视图。`ddl.sql` 的建表段可单独 DROP。
- 生产部署：后端本地 `mvn` 打包上传 jar（遵循 memory：不在生产跑 mvn）；前端 `npm run build` 后部署 `dist`。需同步执行新 `ddl.sql` 段建表。

## 风险

1. **`ProxyHolder` 静态类依赖 service Bean**：`SpringContextUtil.getBean` 在 Spring 容器就绪后才可用，启动早期 `ProxyHolder` 若被调用会回退 Environment 兜底，不崩。
2. **`ProxyConfigService.getEntity` 被高频调用**：靠 5s 内存快照 + volatile 读缓解，开销极小。
3. **401 处理**：需确认 `GlobalResultExceptionHandler` 对 `BusinessException(401, ...)` 的映射，前端拦截器已处理 `code !== 200` 弹错。
4. **并发首初始化**：两个线程同时 `getEntity()` 发现表空，可能各写一行。用 `synchronized` 包住 initRow，或 `saveOrUpdate` 幂等。采用 `synchronized` + `getById(1)` 二次检查。
