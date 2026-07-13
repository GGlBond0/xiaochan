# Design：补回后端ProxyHolder代理逻辑

## 边界与目标

补回两个文件级别的代理能力到 fork `main` 源码，与 distance-3km 改动共存于同一 JAR：
- 新增 `io.github.xiaocan.http.ProxyHolder`（纯静态工具类，从旧 JAR 字节码还原）。
- 改造 `io.github.xiaocan.http.XiaochanHttp`，所有上游 HTTP 经新 `executeWithProxy` 走代理 + 403 重试。

不动：distance-3km 改动（QueryListVO/sortStoreList/filter/监控 within3km）、EnvironmentFile、nginx、前端。

## 数据流

```
XiaochanHttp.postWithRes/searchAddress/getStorePromotionDetail
  → executeWithProxy(reqFn, tag)
       → ProxyHolder.enabled()?  否 → reqFn.apply(null).execute() 直连
       是 → for i in 0..retry:
              proxy = ProxyHolder.getProxy(i>0)   // [ip, port]
              null → throw BusinessException("代理不可用，无法请求小蚕网关")
              reqFn.apply(proxy).setHttpProxy(ip, parseInt(port)).execute()
              status==403 → log.warn + ProxyHolder.invalidate() + continue
              否 → return response
            return null  // 重试耗尽

ProxyHolder.getProxy(force):
  enabled()? 否 → null
  force? 否 且 cachedProxy!=null 且 now-cachedAt < TTL*1000 → cachedProxy
  否 → fetchProxy():
    PROXY_API_URL 空 → log.error → null
    HttpUtil.createGet(url).timeout(8000).execute().body()
    JSON: code==0? 否 → log.error("代理 API 返回异常") → null
    data = JSONArray; null/empty → log.error("代理 API 无可用代理") → null
    obj = data[0]; ip=obj.getString("IP"); port=obj.getInteger("Port")
    ip/port 任一 null → null
    return [ip, String.valueOf(port)]
  成功 → cachedProxy=ret; cachedAt=now; log.info("获取代理: {}:{}", ip, port); return ret

ProxyHolder.invalidate(): cachedProxy=null; cachedAt=0
ProxyHolder.env(key,def): SpringContextUtil.ctx.getEnvironment().getProperty(key) ?: def
```

## 还原后的源码契约（与旧 JAR 字节码 1:1）

### ProxyHolder.java

```java
package io.github.xiaocan.http;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyHolder {
    private static volatile String[] cachedProxy;
    private static volatile long cachedAt;

    public static boolean enabled() {
        return env("PROXY_ENABLED", "false").equalsIgnoreCase("true");
    }
    public static int retry() {
        return Integer.parseInt(env("PROXY_RETRY", "3"));
    }
    public static int requestTimeout() {
        return Integer.parseInt(env("PROXY_REQUEST_TIMEOUT", "5000"));
    }
    public static synchronized String[] getProxy(boolean force) {
        if (!enabled()) return null;
        long ttl = Long.parseLong(env("PROXY_TTL", "28")) * 1000L;
        if (!force && cachedProxy != null && System.currentTimeMillis() - cachedAt < ttl) {
            return cachedProxy;
        }
        String[] p = fetchProxy();
        if (p != null) {
            cachedProxy = p;
            cachedAt = System.currentTimeMillis();
            log.info("获取代理: {}:{}", p[0], p[1]);
        }
        return p;
    }
    public static synchronized void invalidate() {
        cachedProxy = null;
        cachedAt = 0;
    }
    private static String[] fetchProxy() {
        String url = env("PROXY_API_URL", "");
        if (url == null || url.isEmpty()) {
            log.error("PROXY_API_URL 未配置，无法取代理");
            return null;
        }
        String body;
        try {
            body = HttpUtil.createGet(url).timeout(8000).execute().body();
        } catch (Exception e) {
            log.error("代理 API 请求异常: {}", e.getMessage());
            return null;
        }
        JSONObject obj = JSONObject.parseObject(body);
        if (obj == null || obj.getInteger("code") != 0) {
            log.error("代理 API 返回异常: {}", body);
            return null;
        }
        JSONArray data = obj.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            log.error("代理 API 无可用代理: {}", body);
            return null;
        }
        JSONObject first = data.getJSONObject(0);
        String ip = first.getString("IP");
        Integer port = first.getInteger("Port");
        if (ip == null || port == null) return null;
        return new String[]{ip, String.valueOf(port)};
    }
    private static String env(String key, String def) {
        try {
            String v = SpringContextUtil.getApplicationContext().getEnvironment().getProperty(key);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Exception e) {
            return def;
        }
    }
}
```

> 注：旧字节码 `fetchProxy` 未显式 try/catch（异常向上抛被 `getProxy` 外层吞？），但 `env` 有 Exception 表。为稳健加 try/catch 不改变正常路径行为。`enabled()` 字节码 `ldc "true"` 在 `env` 前——经核对是 `env(...).equalsIgnoreCase("true")`，与上面一致。

### XiaochanHttp.executeWithProxy + postWithRes 改造

```java
private HttpResponse executeWithProxy(java.util.function.Function<String[], HttpRequest> reqFn, String tag) {
    if (!ProxyHolder.enabled()) {
        return reqFn.apply(null).execute();
    }
    int retry = ProxyHolder.retry();
    for (int i = 0; i < retry; i++) {
        String[] proxy = ProxyHolder.getProxy(i > 0);
        if (proxy == null) throw new BusinessException("代理不可用，无法请求小蚕网关");
        HttpRequest req = reqFn.apply(proxy);
        req.setHttpProxy(proxy[0], Integer.parseInt(proxy[1]));
        HttpResponse resp = req.execute();
        if (resp.getStatus() == 403) {
            log.warn("{} 经代理 {}:{} 返回 403，换代理重试({}/{})", tag, proxy[0], proxy[1], i + 1, retry);
            ProxyHolder.invalidate();
            continue;
        }
        return resp;
    }
    return null;
}

private String postWithRes(String url, String body, Integer cityCode, String serverName, String methodName) {
    Long timeMillis = System.currentTimeMillis();
    String nami = getNami();
    String ashe = getAshe(timeMillis, serverName, methodName, nami);
    HttpResponse response = executeWithProxy(proxy -> HttpUtil.createPost(url)
            .headerMap(getHeaders(timeMillis, ashe, cityCode, serverName, methodName, nami), true)
            .timeout(ProxyHolder.requestTimeout())
            .body(body), "postWithRes");
    if (response == null || !response.isOk()) {
        throw new BusinessException("状态码错误:" + (response == null ? -1 : response.getStatus()));
    }
    String resBody = response.body();
    response.close();
    return resBody;
}
```

`searchAddress`、`getStorePromotionDetail`：将其中的 `HttpUtil.createPost(...)/createGet(...).execute()` 同样包进 `executeWithProxy`（lambda 构造请求，tag 标注方法名）。`searchList` 经 `postWithRes` 自动继承代理，无需单独改。

## 兼容性

- `PROXY_ENABLED` 未配置 / `false` → `enabled()` false → 直连，行为与当前无代理版一致（向后兼容本地开发）。
- 生产 EnvironmentFile `PROXY_ENABLED=true` → 走代理。
- distance-3km 字段为可选增量，代理层与之正交，互不影响。
- `SpringContextUtil` 必须已注册为 Spring bean（本仓库 `utils/SpringContextUtil` 存在，需确认实现）。

## 构建方式取舍（关键）

fork `main` 的 `application.yaml` 用占位符 `${MYSQL_*:default}`，生产靠 EnvironmentFile 注入真实值。但 GitHub Actions `build-prod.yml` 的「Create Production Config」步骤会**整体重写 application.yaml 为硬编码**（用 `mysql_password` 输入），破坏占位符注入。

**取舍**：
- 方案 A（推荐）：本地 `mvn clean package -DskipTests` 构建，保留占位符 yaml，scp 部署。JAR 启动时 EnvironmentFile 注入真实 MYSQL/PROXY。不依赖 workflow，不碰硬编码问题。
- 方案 B：改 workflow 删除「Create Production Config」步骤（或改为只注入密码占位符），触发 Actions 构建。改动 workflow 需额外评审。

本任务采用 **方案 A**（本地构建），避免引入 workflow 改动的风险。本地需 JDK17 + Maven（后端 `xiaochan-main` 仓库）。

## 回滚 / Rollback shape

- 代码：`ProxyHolder` 与 `XiaochanHttp` 代理改造作为独立 commit，可单独 revert；distance-3km commit 不受影响。
- 部署：scp 前备份 `/opt/xiaocan/xiaocan.jar` → `xiaocan.jar.bak.<ts>`；失败 `cp` 回滚 + restart。
- 代理失败时 `executeWithProxy` 抛 BusinessException，前端看到 500 但不影响系统其余部分；可临时设 `PROXY_ENABLED=false` 直连降级。

## 风险

- `SpringContextUtil` 在 ProxyHolder 首次调用时必须已就绪——HTTP 请求由 controller 触发，Spring 上下文已启动，安全。但静态初始化期不要调用 `env()`（`ProxyHolder` 无 static 块调用 env，安全）。
- 代理 API 返回格式若与旧 JAR 时代不同（字段名 IP/Port），需核对。已从字节码确认字段名 `IP`/`Port`。
- 本机构建需确认 JDK17 + Maven 可用。
