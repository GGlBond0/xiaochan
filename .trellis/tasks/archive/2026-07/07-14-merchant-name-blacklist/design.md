# 商家名称关键字黑名单 — 技术设计

## 边界与范围

本功能跨前后端两个仓库：
- 后端：`C:\D\AI\Projects\xiaocan\xiaocan-main`（新增配置七件套 + Holder + 过滤插入点 + ddl）
- 前端：`C:\D\AI\Projects\xiaocan\xiaocan-front-main`（SettingsView 新增配置区块）

仅作用于"监控推送"与"抢单"两处。不涉及代理、登录、监控配置/抢单配置 CRUD 等既有功能。

## 数据模型

新增表 `merchant_blacklist_config`，全局单份（id 固定 1），对齐 `proxy_config` 形态：

```sql
DROP TABLE IF EXISTS `merchant_blacklist_config`;
CREATE TABLE `merchant_blacklist_config` (
  `id`          INT NOT NULL AUTO_INCREMENT COMMENT '主键ID，固定1（全局单份）',
  `enabled`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用黑名单过滤',
  `keywords`    TEXT NULL COMMENT '关键字规则，一行一条；行内 & 表示 AND；多行为 OR；大小写不敏感子串包含',
  `create_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1) NULL DEFAULT 0 COMMENT '逻辑删除标志',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商家名称关键字黑名单全局配置表';
```

`keywords` 用 TEXT 存多行规则文本，前端 textarea 直读直写，后端解析为规则集。

## 匹配算法

关键字规则解析与匹配收敛到一个工具方法，监控侧与抢单侧共用，避免两处实现漂移：

```
规则文本 -> 按行切分 -> 去空白 -> 忽略空行 -> 每行按 '&' 切分多词 -> 去空白 -> 得 List<List<String>> 规则集
匹配(storeName): 若 enabled 且规则集非空 且 storeName != null：
   对每条规则(词列表): 规则命中 = 该行所有词都以子串形式出现在 lower(storeName) 中
   任一行命中 => isBlacklisted = true
否则 => false
```

- 大小写不敏感：storeName 与词统一 `toLowerCase` 后比较。
- 子串包含：用 `contains`，不要求整词边界。
- 空规则 / disabled / null 商家名：返回 false（不过滤）。

## 后端组件设计

### MerchantBlacklistHolder（静态工具类，对齐 ProxyHolder）
- `static boolean isBlacklisted(String storeName)`：供监控/抢单调用方零成本接入。
- `static void invalidate()`：清配置快照与（如有）解析缓存，下次请求重新读 DB。
- `static void loadCfg()`：经 `SpringContextUtil.getBean(MerchantBlacklistService.class).getEntity()` 读 DB，带 5s 内存快照；异常/容器未就绪回退"禁用+空规则"（即不过滤），保证不阻断主流程。
- 解析后的规则集与 enabled 一并缓存于快照，避免每次匹配重解析。
- 懒初始化：表空时首次访问以默认值（enabled=0, keywords=null）落库，synchronized 防并发各写一行（对齐 ProxyConfigServiceImpl.ensureRow）。

### MerchantBlacklistService / Impl
- `extends IService<MerchantBlacklistEntity>`
- `MerchantBlacklistVO getConfig()`：ensureRow + copyProperties 转 VO。
- `void updateConfig(MerchantBlacklistDTO dto)`：校验 + updateById + 调 `MerchantBlacklistHolder.invalidate()` 使缓存失效。
- `MerchantBlacklistEntity getEntity()`：供 Holder 运行时读取。

### MerchantBlacklistController
- `@RequestMapping("/api/blacklist")`
- `GET /config` → `get()`：读全局配置，先 `userService.getByCurrentRequest()` 鉴权。
- `PUT /config` → `update(@Valid @RequestBody MerchantBlacklistDTO)`：先鉴权再落库。
- 401 仍走 `GlobalResultExceptionHandler` 映射成 HTTP 200 + `success=false`（与代理配置一致）。

### Entity / Mapper / DTO / VO
- `MerchantBlacklistEntity`：`@TableName("merchant_blacklist_config")`，`@TableLogic deleted`，自动时间列。
- `MerchantBlacklistMapper`：`extends BaseMapper<MerchantBlacklistEntity>`，无自定义方法。
- `MerchantBlacklistDTO`：`enabled` + `keywords`，`@Min`/长度校验（keywords 允许空串/null，但可限最大长度防滥用，如 4000）。
- `MerchantBlacklistVO`：`enabled` + `keywords`。

## 过滤插入点

### 监控推送
- `StoreTask.filterStoreInfos`（`StoreTask.java:111`）：在 STREAM 链**首部**追加
  `.filter(storeInfo -> !MerchantBlacklistHolder.isBlacklisted(storeInfo.getName()))`
  该 filter 在 STORE_ACTIVITY（:114）与 STORE_KEYWORD（:124）两个分支之前，统一拦在分支前。
- `MinimumPayService.filterStoreInfos`（`MinimumPayService.java:52`）：同样在 stream 首部追加同一 filter。
- 因 filterStoreInfos 是 stream 过滤，命中黑名单的门店不会进入后续 sendMessage/写历史，天然实现"不推送、不写历史"。

### 抢单
- `GrabServiceImpl.doGrab`（`GrabServiceImpl.java:343`）：在 `:410` 拿到 `storeName` 后、`:418` 重试循环前插入判断：
  - `if (MerchantBlacklistHolder.isBlacklisted(storeName))`：
    - 调 `saveHistory` 记一条历史，状态/结果标记为"黑名单拦截"（复用现有 history 写入方法，结果文案体现被黑名单拦截）。
    - 调 `pushService.pushToUser(...)` 推一条失败/提示通知（复用 doGrab 既有 PushService 注入，见 `GrabServiceImpl.java:59`）。
    - `return;`（不发抢单请求）。
  - 需确认 doGrab 中 storeName 为 null（promoSnapshot 为 null）时的既有兜底分支不会被黑名单逻辑误伤——isBlacklisted(null) 返回 false，故 null 商家名直接放行走原逻辑。

## 数据流

```
前端 SettingsView 商家黑名单区块
  --GET /api/blacklist/config--> MerchantBlacklistController.get -> Service.getConfig -> VO
  --PUT /api/blacklist/config--> Controller.update -> Service.updateConfig(updateById + Holder.invalidate)
后端运行时：
  StoreTask/MinimumPayService.filterStoreInfos -> MerchantBlacklistHolder.isBlacklisted(name) -> 读快照(5s) -> 命中则剔除
  GrabServiceImpl.doGrab 拿 storeName -> Holder.isBlacklisted -> 命中则记历史+推通知+return
Holder 快照过期/被 invalidate -> loadCfg -> Service.getEntity -> DB
```

## 兼容性与回滚
- enabled=false / keywords 空：isBlacklisted 恒 false，监控/抢单行为与改动前完全一致，零回归。
- Holder 异常回退"不过滤"：即便 DB 读失败也不阻断监控/抢单主流程（对齐 ProxyHolder 兜底哲学）。
- 回滚：删除新增类与 ddl 段、还原 filterStoreInfos/doGrab 插入点即可；无既有表结构变更、无既有方法签名变更。
- 部署顺序：先执行 ddl.sql 新段建表，再部署后端 jar，再部署前端 dist。建表前若新 jar 已上线，Holder 懒初始化会在首次访问时报错并回退"不过滤"（不阻断），但应按顺序部署以避免告警。

## 风险与权衡
- 全局单份 vs 每用户：选全局单份（对齐代理配置，符合"黑名单是站点级策略"语义，且复用成本最低）。若后续需每用户隔离，可参考 grab_config 的 user_id 模式重构。
- keywords 用 TEXT 而非 JSON：规则是行式文本，textarea 直读直写最简单，避免 JSON 转义噪音；解析逻辑收敛在 Holder 一处。
- 监控侧 filterStoreInfos 在 BaseTask 模板的钩子里，两个子类各自追加 filter 行；未抽到 BaseTask 基类是为了最小侵入，代价是两处各加一行（可接受，且语义直观）。
