package io.github.xiaocan.http;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.xiaocan.model.entity.ProxyConfigEntity;
import io.github.xiaocan.service.ProxyConfigService;
import io.github.xiaocan.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 代理持有者：从全局配置（数据库 proxy_config 表）读取代理 IP 拉取参数，TTL 缓存，失效可换。
 * 配置来源优先级：运行时读 ProxyConfigService.getEntity()（带内存快照缓存），
 * 异常或容器未就绪时回退 Spring Environment（systemd EnvironmentFile 注入）默认值。
 * 修改配置经 /api/proxy/config 落库后调用 invalidate() 即时生效，无需重启服务。
 * 对外方法签名不变，调用方（XiaochanHttp 等）零改动。
 *
 * 并发设计：
 * - getProxy 不在持锁期间执行同步 HTTP（最长 8s），避免阻塞 invalidate 与并发取代理；
 *   仅在读/写 cachedProxy 时短暂持锁。
 * - loadCfg 不在持 ProxyHolder.class 锁时调用 ProxyConfigService，避免与
 *   ProxyConfigServiceImpl.updateConfig 形成反向锁顺序死锁。
 */
@Slf4j
public class ProxyHolder {

    private static volatile String[] cachedProxy;
    private static volatile long cachedAt;

    /** 配置内存快照：减少运行时打 DB 频率 */
    private static volatile ProxyConfigEntity cfgSnapshot;
    private static volatile long cfgLoadedAt;
    /** 配置快照刷新间隔（毫秒）：保存后 invalidate() 也会立即清快照，无需等过期 */
    private static final long CFG_TTL = 5000L;

    public static boolean enabled() {
        return enabledOf(loadCfg());
    }

    public static int retry() {
        ProxyConfigEntity c = loadCfg();
        if (c != null && c.getRetry() != null) {
            return c.getRetry();
        }
        return Integer.parseInt(env("PROXY_RETRY", "3"));
    }

    public static int requestTimeout() {
        ProxyConfigEntity c = loadCfg();
        if (c != null && c.getRequestTimeout() != null) {
            return c.getRequestTimeout();
        }
        return Integer.parseInt(env("PROXY_REQUEST_TIMEOUT", "5000"));
    }

    /**
     * 取代理：一次读取配置后，锁内仅查缓存，锁外执行 HTTP 拉取，锁内仅写回缓存。
     * 避免慢 HTTP 长时间持锁阻塞 invalidate（保证保存后即时生效）。
     */
    public static String[] getProxy(boolean force) {
        ProxyConfigEntity c = loadCfg();
        if (!enabledOf(c)) {
            return null;
        }
        long ttl = ttlOf(c) * 1000L;

        // 锁内仅查缓存
        String[] cached = readCache(force, ttl);
        if (cached != null) {
            return cached;
        }

        // 锁外执行同步 HTTP 拉取，避免持锁阻塞
        String[] p = fetchProxy(apiUrlOf(c));
        if (p != null) {
            writeCache(p);
            log.info("获取代理: {}:{}", p[0], p[1]);
        }
        return p;
    }

    /**
     * 失效缓存：清代理缓存与配置快照，使下次取代理/读配置重读 DB。
     * ProxyConfigServiceImpl.updateConfig 落库后调用本方法实现即时生效。
     */
    public static synchronized void invalidate() {
        cachedProxy = null;
        cachedAt = 0;
        cfgSnapshot = null;
        cfgLoadedAt = 0;
    }

    // ---------- 配置读取（entity + Environment 兜底） ----------

    private static boolean enabledOf(ProxyConfigEntity c) {
        if (c != null) {
            return Boolean.TRUE.equals(c.getEnabled());
        }
        return env("PROXY_ENABLED", "false").equalsIgnoreCase("true");
    }

    private static int ttlOf(ProxyConfigEntity c) {
        if (c != null && c.getTtl() != null) {
            return c.getTtl();
        }
        return Integer.parseInt(env("PROXY_TTL", "28"));
    }

    private static String apiUrlOf(ProxyConfigEntity c) {
        if (c != null && c.getApiUrl() != null) {
            return c.getApiUrl();
        }
        return env("PROXY_API_URL", "");
    }

    /**
     * 读取配置快照：CFG_TTL 内复用内存快照，过期或无快照则从 ProxyConfigService 重读。
     * service 不可用（容器未就绪/异常）时返回 null，调用方回退 Environment 兜底。
     * 注意：service 调用在锁外执行，避免持 ProxyHolder.class 锁时进入 Service 实例锁
     *       而与 updateConfig 形成反向锁顺序。
     */
    private static ProxyConfigEntity loadCfg() {
        ProxyConfigEntity snap = cfgSnapshot;
        if (snap != null && System.currentTimeMillis() - cfgLoadedAt < CFG_TTL) {
            return snap;
        }
        // 锁外调 service 取最新 entity
        ProxyConfigEntity entity = null;
        try {
            ProxyConfigService service = SpringContextUtil.getBean(ProxyConfigService.class);
            entity = service.getEntity();
        } catch (Exception e) {
            log.warn("读取代理配置失败，回退环境变量默认值: {}", e.getMessage());
        }
        if (entity == null) {
            return null;
        }
        // 锁内仅写快照
        synchronized (ProxyHolder.class) {
            cfgSnapshot = entity;
            cfgLoadedAt = System.currentTimeMillis();
            return entity;
        }
    }

    // ---------- 代理缓存读写（锁内仅做内存操作） ----------

    private static synchronized String[] readCache(boolean force, long ttlMs) {
        if (!force && cachedProxy != null && System.currentTimeMillis() - cachedAt < ttlMs) {
            return cachedProxy;
        }
        return null;
    }

    private static synchronized void writeCache(String[] proxy) {
        cachedProxy = proxy;
        cachedAt = System.currentTimeMillis();
    }

    /**
     * 拉取代理 IP：整体兜底解析异常，任何异常都返回 null（视为无可用代理），
     * 由调用方走重试/换代理路径，避免异常冒泡中断请求处理。
     */
    private static String[] fetchProxy(String url) {
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
        try {
            JSONObject obj = JSONObject.parseObject(body);
            if (obj == null || obj.getInteger("code") == null || obj.getInteger("code") != 0) {
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
            if (ip == null || port == null) {
                return null;
            }
            return new String[]{ip, String.valueOf(port)};
        } catch (Exception e) {
            log.error("代理 API 响应解析异常: {}", e.getMessage());
            return null;
        }
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
