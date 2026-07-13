# 补回后端ProxyHolder代理逻辑使distance-3km可上线

## Goal

从生产旧 JAR（`xiaocan.jar.bak.20260714-035513`）字节码还原 `ProxyHolder` 代理机制与 `XiaochanHttp.executeWithProxy` 代理调用逻辑，补回 fork `GGlBond0/xiaocan` `main` 分支缺失的代理代码，与已完成的 distance-3km 改动合并，重新构建并部署到生产，使首页能经代理正常访问上游小蚕网关（不再 403），并使 distance-3km 的 3km 筛选/距离排序端到端生效。

## Background / 根因（实测，2026-07-14）

- 生产依赖代理访问上游 `gw.xiaocantech.com`：`ProxyHolder` 从环境变量 `PROXY_API_URL`（携趣代理 `api.xiequ.cn`）拉代理 IP（端口 3828），TTL 28s 缓存，403 时 `invalidate()` 换代理重试最多 3 次。`XiaochanHttp.executeWithProxy` 封装「取代理→setHttpProxy→execute→403 重试」。
- **fork `main` 与 `ci/github-actions` 两分支都没有 `ProxyHolder`，`XiaochanHttp.postWithRes` 是无代理直连版**（本仓库当前 `src/main/java/io/github/xiaocan/http/XiaochanHttp.java`）。上游 `lyrric/xiaochan` 也没有。带代理版本只在生产旧 JAR 字节码中。
- distance-3km 改动（commit `cad62c8`）基于无代理的 `main`，构建出的 JAR 直连上游 → 403 → 首页空。已回滚到带代理旧 JAR（首页恢复但无 3km 功能）。
- 代理配置在 systemd `EnvironmentFile=/etc/xiaocan/xiaocan.env`：`PROXY_ENABLED=true` `PROXY_API_URL=http://api.xiequ.cn/VAD/GetIp.aspx?...uid=183587&vkey=...` `PROXY_TTL=28` `PROXY_RETRY=3` `PROXY_REQUEST_TIMEOUT=5000`。`env()` 经 `SpringContextUtil.getApplicationContext().getEnvironment().getProperty()` 读取（Spring Environment 读 EnvironmentFile）。
- `XiaochanHttp` 需改造的方法（旧 JAR 字节码确认）：`postWithRes`、`searchList`（经 `postWithRes`）、`searchAddress`（独立 HTTP，需走代理）、`getStorePromotionDetail`（若有 HTTP 调用，需走代理）。`MessageHttp` 若也访问上游需同步评估。

## Requirements

### R1 还原 ProxyHolder
- 新增 `src/main/java/io/github/xiaocan/http/ProxyHolder.java`，行为与旧 JAR 字节码一致：
  - `enabled()`：读 `PROXY_ENABLED`，默认 false（空/异常时）。
  - `retry()`：读 `PROXY_RETRY`，默认 3。
  - `requestTimeout()`：读 `PROXY_REQUEST_TIMEOUT`，默认 5000。
  - `getProxy(boolean force)`：synchronized；`enabled=false` 返回 null；`force=false` 且缓存未过期（`PROXY_TTL`*1000ms）返回缓存；否则 `fetchProxy()`，成功则缓存并 `log.info("获取代理: {}:{}", ip, port)`，返回 `String[]{ip, port}`。
  - `invalidate()`：synchronized；清空 `cachedProxy`/`cachedAt`。
  - `fetchProxy()`：从 `PROXY_API_URL` GET（8s 超时），JSON 解析 `code==0` 取 `data` 数组首个元素的 `ip`/`port`，失败 `log.error` 返回 null。未配置 `PROXY_API_URL` 时 `log.error("PROXY_API_URL 未配置...")` 返回 null。
  - `env(key, def)`：经 `SpringContextUtil.getApplicationContext().getEnvironment().getProperty(key)`，异常/空返回 `def`。

### R2 改造 XiaochanHttp 走代理
- 新增 `executeWithProxy(Function<String[], HttpRequest> reqFn, String tag)`：
  - `enabled=false` → `reqFn.apply(null).execute()` 直连。
  - 否则循环 `retry` 次：`getProxy(i>0)` → null 抛 `BusinessException("代理不可用，无法请求小蚕网关")` → `reqFn.apply(proxy).setHttpProxy(ip, parseInt(port)).execute()` → status==403 则 `log.warn` + `invalidate()` 继续，否则返回 response。
- `postWithRes` 改为经 `executeWithProxy` 执行（用 lambda 构造 HttpRequest，含 headers/body/timeout）。`timeout` 用 `ProxyHolder.requestTimeout()`。
- `searchList`、`searchAddress`、`getStorePromotionDetail` 中所有对上游的 HTTP 请求统一经 `executeWithProxy`。
- 保留现有 distance-3km 改动（QueryListVO.within3km、sortStoreList orderType=4、filter within3km、监控 within3km）不动。

### R3 构建与部署
- 本地 `mvn -q -DskipTests compile` 验证编译通过。
- 构建并部署 JAR 到 `/opt/xiaocan/xiaocan.jar`（备份旧版），`systemctl restart xiaocan`，确认 EnvironmentFile 的 `PROXY_*` 生效。构建方式见 design.md（workflow 硬编码 yaml 问题需处理）。

### R4 验证（端到端）
- `curl /api/xiaochan/query`（默认 orderType=1）返回门店列表（经代理，非 403）。
- `within3km=true` → 返回门店全部 `distance<=3000`。
- `orderType=4` → 按 distance 升序，null 排末尾。
- `within3km=true`+`orderType=4` 组合正常。
- 监控任务 MINIMUM_PAY/STORE_KEYWORD 带 within3km 命中门店 distance<=3000。
- 日志出现 `获取代理: <ip>:3828`，无持续 403。
- 浏览器 http://121.91.175.192:8088/ 首页有门店、3km 开关生效、距离排序生效（用户确认）。

## Constraints

- 不改变 distance-3km 已有改动语义，仅补回代理基础。
- ProxyHolder 行为必须与旧 JAR 字节码一致（避免引入新代理 bug）。
- 不改 EnvironmentFile `/etc/xiaocan/xiaocan.env` 内容（凭据敏感）。
- 不改 nginx 配置。
- 依赖现有 `SpringContextUtil`（`io.github.xiaocan.utils.SpringContextUtil`，本仓库已有）。

## Acceptance Criteria

- [ ] `ProxyHolder.java` 新增，行为与旧 JAR 字节码一致（env/retry/enabled/getProxy/invalidate/fetchProxy）。
- [ ] `XiaochanHttp` 所有上游 HTTP 经 `executeWithProxy`，403 自动换代理重试。
- [ ] `mvn compile` 通过。
- [ ] 构建并部署 JAR，systemd EnvironmentFile 的 PROXY_* 生效，日志出现 `获取代理`。
- [ ] `/api/xiaochan/query` 默认请求返回门店（非空、非 403）。
- [ ] `within3km=true` 返回门店全部 distance<=3000；`orderType=4` 距离升序。
- [ ] 浏览器端到端 3km 开关/距离排序生效（用户确认）。
- [ ] 回滚点：保留旧 JAR 备份，可 `cp` 回滚 + restart。

## 回滚点

- 部署前备份当前生产 JAR（带代理旧版）为 `xiaocan.jar.bak.<ts>`。
- 失败时 `cp /opt/xiaocan/xiaocan.jar.bak.<ts> /opt/xiaocan/xiaocan.jar && systemctl restart xiaocan`。
- 代码层：代理与 distance-3km 解耦，代理 bug 可单独回退 XiaochanHttp/ProxyHolder 而保留 distance-3km 字段。

## Out of Scope

- 前端 dist（已部署，本任务不动）。
- EnvironmentFile 凭据变更、nginx 变更。
- 代理 API 服务商更换。
- 改造 workflow 的硬编码 yaml 问题（本任务仅评估，若阻塞则最小修复）。
