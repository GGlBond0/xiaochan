# 代理 IP 池配置可视化设置页

## 背景与现状

后端 `ProxyHolder`（`io.github.xiaocan.http.ProxyHolder`）是全局唯一的代理 IP 池持有者，当前通过 Spring Environment 读取环境变量配置：

- `PROXY_ENABLED`（是否启用代理，默认 `false`）
- `PROXY_API_URL`（拉取代理 IP 的上游 API 地址）
- `PROXY_TTL`（代理缓存有效期秒数，默认 `28`）
- `PROXY_RETRY`（请求失败换代理重试次数，默认 `3`）
- `PROXY_REQUEST_TIMEOUT`（上游请求超时毫秒，默认 `5000`）

这些值在生产经 systemd EnvironmentFile 注入，改动需改 EnvironmentFile 并重启服务。`ProxyHolder` 为静态工具类，被 `XiaochanHttp.executeWithProxy` 等多处调用，提供 `enabled() / retry() / requestTimeout() / getProxy(force) / invalidate()` 等静态方法，内部用 `volatile` 字段做 TTL 缓存。

用户希望：把这些配置搬到前端可视化设置页修改、后端持久化，保存后即时生效、无需重启服务，并在顶部导航加一个"设置"入口。

## 目标

1. 后端新增代理配置的读写能力，配置存入数据库（全局单份），运行时由 `ProxyHolder` 直接读 DB（带内存缓存），保存后立即生效。
2. 新增 `/api/proxy/config` 读写接口，遵循现有 `BaseResult` + Controller/Service/ServiceImpl/Mapper/Entity 分层风格。
3. 前端新增"设置"页，提供表单编辑这 5 项配置。
4. 前端顶部 `NavBar` 新增"设置"导航入口，路由 `/settings`。

## 范围

- 配置粒度：**全局单份**（数据库一行），所有登录用户共享同一套代理。
- 编辑权限：**所有登录用户均可读可改**（系统当前无管理员角色概念，沿用 token 登录态即可，不引入权限分级）。
- 生效方式：**运行时读 DB 即时生效**，无需重启服务。环境变量降级为首启动默认值/兜底。
- 不改动 `XiaochanHttp` 等调用方对 `ProxyHolder` 的方法签名（`enabled() / retry() / requestTimeout() / getProxy(force) / invalidate()`），保持调用方零改动。

## 约束

- 不得在生产服务器执行 `mvn` 构建（见 memory `prod-build-avoid-server`）；本任务只改代码，构建验证在本机完成。
- `ProxyHolder` 是静态类、被多线程并发调用（定时任务 + 请求线程），读 DB 路径需保证线程安全与低开销（内存缓存 + 失效机制）。
- 保持与现有代码风格一致：MyBatis-Plus `ServiceImpl`、`LambdaQueryWrapper`、`BaseResult.ok()`、`@TableLogic` 逻辑删除、`userService.getByCurrentRequest()` 解析当前用户。

## 验收标准

1. 后端启动后，数据库 `proxy_config` 表存在（DDL 已追加到 `ddl.sql`），首启动若表为空则用环境变量默认值自动初始化一行。
2. `GET /api/proxy/config` 返回当前全局配置（5 项），未登录返回 401。
3. `PUT /api/proxy/config` 保存配置后，`ProxyHolder` 下次取代理即用新值，**无需重启服务**。
4. 前端顶部 NavBar 出现"设置"入口，点击进入 `/settings` 设置页。
5. 设置页表单可编辑 5 项配置，带基本校验（enabled 布尔、ttl/retry/timeout 正整数、apiUrl 非空当 enabled=true），保存成功有提示并回显。
6. 修改并保存后，后端日志可见"获取代理"使用新配置生效（或通过接口验证 `enabled()` 返回值变化）。
7. 现有代理相关功能（门店查询、抢单、卡券查询）在配置变更后行为正常，不因改造而报错。

## 非目标

- 不做按用户隔离的代理配置。
- 不做管理员权限分级。
- 不重构 `ProxyHolder` 为 Spring Bean（保持静态工具类形态，内部通过 `SpringContextUtil.getBean` 获取配置 Service）。后续如需可再重构。
- 不做代理 IP 的可视化列表/手动增删（仍由上游 API 拉取）。
