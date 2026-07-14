# Design — 后端日志脱敏与代理超时治理

## SEC-1 修复方案

### 1.1 日志级别
`src/main/resources/logback.xml` root level 由 `DEBUG` 改为 `INFO`。
- DEBUG 是 MyBatis SQL（`==>`/`<==`）全量打印的源头，含 token/spt/JWT/代理URL 参数。
- 改 INFO 后 MyBatis 默认不再打 SQL 详情（mybatis-plus 默认 log impl 不在 INFO 打 SQL）。
- 若需保留 SQL 排错，可在 logback 单独把 `io.github.xiaocan` 包设 INFO、MyBatis mapper 包设 WARN；本方案直接 root=INFO 简化。

### 1.2 敏感字段脱敏
业务层显式打印的敏感信息：
- `GrabServiceImpl:255` `log.info("保存抢单配置请求: {}", dto)` —— dto 含 promotionId/silkId 等，本身不含密钥，但 DTO 的 toString 可能含登录态相关。改为不打印完整 dto，或打印关键字段（configId/promotionId）。
- `PushServiceImpl:99` `log.error("推送失败 spt={} summary={}", spt, summary)` —— spt 明文。改为打码（spt 前6后4，中间*）。
- `SptService:45/71` `log.info("...spt:{}...", spt)` —— 同样打码。
- `ProxyHolder` fetchProxy 的 `log.error("代理 API 返回异常: {}", body)` —— body 可能含代理 IP 列表，非密钥但可接受；代理 api_url 本身在配置表/日志不打印明文 vkey。

新增工具方法 `mask(String)`：spt/token/JWT 截取首尾保留中间打码（如 `SPT_zNQd...***...xa8`）。放 `util` 包复用。

### 1.3 MyBatis 参数
root=INFO 后，MyBatis 默认 `org.apache.ibatis` 不在 INFO 打 SQL。确认 `application.yml` 无 `mybatis-plus.configuration.log-impl` 设为 StdOutImpl。若设了，改为默认或 Slf4jImpl(WARN)。

## HOME-1/GRAB-1 修复方案

### 2.1 executeWithProxy 网络异常换代理重试
`XiaochanHttp.executeWithProxy`（line 92-114）现状：`req.execute()` 抛 SocketTimeout/Connection reset 直接冒泡，不重试不换代理。

改为：catch 网络异常 → 失效当前代理（`ProxyHolder.invalidate()`）→ 换代理重试，计入 retry 次数。伪代码：
```java
for (int i = 0; i < retry; i++) {
    String[] proxy = ProxyHolder.getProxy(i > 0);
    if (proxy == null) throw new BusinessException("代理不可用...");
    HttpRequest req = reqFn.apply(proxy);
    req.setHttpProxy(proxy[0], Integer.parseInt(proxy[1]));
    try {
        HttpResponse response = req.execute();
        if (response.getStatus() == 403) {
            log.warn(...403换代理...);
            response.close();
            ProxyHolder.invalidate();
            continue;
        }
        return response;
    } catch (Exception e) {  // SocketTimeout/Connection reset 等
        log.warn("{} 经代理 {}:{} 请求异常，换代理重试({}/{}): {}", tag, proxy[0], proxy[1], i+1, retry, e.getMessage());
        ProxyHolder.invalidate();
        continue;  // 换代理重试
    }
}
return null;  // 全部重试失败
```
- 关键：网络异常 catch 后 invalidate 换代理，而非冒泡。
- 重试次数复用 `ProxyHolder.retry()`（配置3）。

### 2.2 超时与降级
- 单次超时维持 `requestTimeout()`（5000ms，用户配置），不硬改。
- 全部重试失败后，`executeWithProxy` 返回 null，调用方（postWithRes）已有 `response==null` 处理抛 BusinessException。首页 doGetList catch 返回 emptyList，不影响其他页。
- 可选优化：代理连续失败 N 次后，下一次请求临时直连（降级）。本任务先做"异常换代理重试"，直连降级列为后续，避免改动过大。

### 2.3 抢单 GRAB-1
- doGrab（GrabServiceImpl:417-461）已有重试循环，请求异常 catch（line 421）会重试（enableRetry 时）。
- 修 executeWithProxy 后，单次 grab 请求内部已换代理重试3次；外层 doGrab 再重试。需注意重试次数叠加导致总时长。但比"一次失败就放弃"好。
- code=-1（网络异常）在 executeWithProxy 已换代理重试过；doGrab 外层对 code=-1 的重试逻辑保持不变（line 452 `code != 4 break`——网络异常 code=-1 会 break，但因内层已重试，可接受；若想外层也重试网络异常，可在 line 452 增 `&& code != -1` 条件，但保守起见本次不改 doGrab 外层，仅改 executeWithProxy 内层）。

## 兼容性 / 回滚
- 后端 jar 改动，需重新构建部署。
- 回滚：备份旧 jar `/opt/xiaocan/xiaocan.jar` → `xiaocan.jar.bak.<ts>`，出问题 `cp` 回滚 + `systemctl restart xiaocan`。
- 部署不涉及数据库 schema 变更。

## 风险
- executeWithProxy 改动影响所有上游请求（首页/搜索/抢单/卡券）。需本地编译 + 线上充分验证。
- 日志降级可能影响排查便利性，权衡后 INFO + 关键脱敏为合理点。
