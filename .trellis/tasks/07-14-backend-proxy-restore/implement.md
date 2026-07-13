# Implement：补回后端ProxyHolder代理逻辑

## 执行顺序与检查点

### 准备核对
- [ ] P1. 确认本机 JDK17 + Maven 可用（`java -version` / `mvn -v`）；若本机无 java（已知本机无），改在**服务器上**构建（服务器有 java），或本地安装 JDK17。决定构建机。
- [ ] P2. 确认 `src/main/java/io/github/xiaocan/utils/SpringContextUtil.java` 存在且为 Spring bean（ProxyHolder.env 依赖）。
- [ ] P3. 读取当前 `XiaochanHttp.java` 全文，定位 `postWithRes`/`searchList`/`searchAddress`/`getStorePromotionDetail` 中所有 `HttpUtil.create*().execute()` 调用点。

### 实现（Phase 2.1，主会话派 trellis-implement 或自行 Edit）
- [ ] I1. 新增 `src/main/java/io/github/xiaocan/http/ProxyHolder.java`（按 design.md 源码契约 1:1）。
- [ ] I2. `XiaochanHttp` 新增 `executeWithProxy(Function<String[],HttpRequest>, String)`（按 design.md）。
- [ ] I3. `postWithRes` 改为经 `executeWithProxy`（lambda 构造 createPost + headers + body + timeout(ProxyHolder.requestTimeout())）。
- [ ] I4. `searchAddress` 的 HTTP 调用经 `executeWithProxy`（tag="searchAddress"）。
- [ ] I5. `getStorePromotionDetail` 的 HTTP 调用经 `executeWithProxy`（若存在上游请求）。
- [ ] I6. 评估 `MessageHttp` 是否访问上游小蚕网关；若是，同样走代理（本任务范围边缘，视实际而定）。
- [ ] I7. 不动 distance-3km 改动（QueryListVO/sortStoreList/filter/监控 within3km）。

### 编译验证（gate）
- [ ] C1. `mvn -q -DskipTests compile` 通过（构建机上）。若在服务器构建：`cd /opt/xiaocan-build && mvn ...`（需先把源码传上去）。
- [ ] C2. `mvn clean package -DskipTests` 产出 `target/xiaocan.jar`，确认 `unzip -l` 含 `ProxyHolder.class` 与改动后的 `XiaochanHttp.class`。

### 部署
- [ ] D1. 备份生产 JAR：`ssh root@121.91.175.192 'cp -p /opt/xiaocan/xiaocan.jar /opt/xiaocan/xiaocan.jar.bak.<ts>'`。
- [ ] D2. scp 新 JAR 到 `/opt/xiaocan/xiaocan.jar`，`chown xiaocan:xiaocan`，`chmod 644`。
- [ ] D3. `systemctl restart xiaocan`，确认 EnvironmentFile `/etc/xiaocan/xiaocan.env` 的 `PROXY_*` 存在且未动。
- [ ] D4. `systemctl is-active xiaocan` active；启动日志无异常；`Started XiaocanServer`。

### 验证（gate，V1-V6 + 浏览器）
- [ ] V1. `curl /api/xiaochan/query`（orderType=1，带 cityCode/lat/lng）返回门店列表（非空、非 403）。
- [ ] V2. 日志出现 `获取代理: <ip>:3828`，无持续 `状态码错误:403`。
- [ ] V3. `within3km=true` → 返回门店全部 `distance<=3000`。
- [ ] V4. `orderType=4` → 距离升序，null 排末尾。
- [ ] V5. `within3km=true` + `orderType=4` 组合正常。
- [ ] V6. 监控任务 MINIMUM_PAY/STORE_KEYWORD 带 within3km 命中 distance<=3000（看下次 cron 或手动触发）。
- [ ] V7. 浏览器 http://121.91.175.192:8088/ 首页有门店、3km 开关生效、距离排序生效（用户确认）。
- [ ] V8. 确认旧 JAR 备份完整可回滚。

## Review Gates
- G1：I1-I2 后先 `mvn compile` 确认 ProxyHolder + executeWithProxy 编译通过，再做 I3-I5。
- G2：C1-C2 编译打包通过再部署。
- G3：V1-V2（代理恢复）通过后再验 V3-V5（3km 功能）。
- G4：V7 浏览器端到端用户确认后再 finish。

## 回滚点
- 部署失败/回归：`cp /opt/xiaocan/xiaocan.jar.bak.<ts> /opt/xiaocan/xiaocan.jar && systemctl restart xiaocan`。
- 代码层：ProxyHolder + XiaochanHttp 代理改造为独立 commit，`git revert` 不影响 distance-3km commit。
- 代理降级：临时 `PROXY_ENABLED=false`（改 EnvironmentFile 后 restart）直连，但会回到 403 问题，仅应急。

## 构建机决定
本机已知无 java。两个选项：
- 服务器构建：scp 源码（或 git clone fork 到服务器）→ 服务器 `mvn package`（服务器有 java 17.0.19）→ 本地 JAR 直接部署。最简，免装环境。
- 本地装 JDK17：winget 安装，本地构建后 scp JAR。需装环境。
→ 倾向服务器构建（已确认服务器 java 可用），实现阶段最终确定。
