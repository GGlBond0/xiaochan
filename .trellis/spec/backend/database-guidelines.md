# Database Guidelines

> 数据库约定（MyBatis-Plus + MySQL）。

---

## Overview

ORM 用 MyBatis-Plus 3.5.9（`mybatis-plus-spring-boot3-starter`）+ MySQL（`mysql-connector-j`）。**无 XML mapper**——查询全部用 Lambda 链式。数据库 schema 见仓库根 `ddl.sql`。

---

## Query Patterns

- mapper 继承 `BaseMapper<XxxEntity>` + `@Mapper`，**不写自定义 SQL 方法**。
- 查询用 `lambdaQuery()` / `LambdaQueryWrapper`，例：
  ```java
  // service/impl 内
  lambdaQuery().eq(XxxEntity::getField, value).page(new Page<>(pageNum, pageSize));
  ```
- 批量用 `saveBatch` / `removeByIds`（IService 提供）。
- 分页用 `Page<T>`，`MybatisPlusConfig` 已注册 `PaginationInnerInterceptor(DbType.MYSQL)`；controller 可直接返回 `Page<VO>`。
- 实体字段可直接用枚举类型（如 `MonitorTypeEnums type`），依赖 MyBatis-Plus 枚举处理。

---

## Migrations

- **无迁移工具**（无 Flyway/Liquibase）。schema 由根目录 `ddl.sql` 手工维护。
- 改表：直接编辑 `ddl.sql` + 生产手动执行。本任务不强求引入迁移工具。

---

## Naming Conventions

- 表名/字段名全下划线：`monitor_config`、`store_pushed_history`、`user_id`、`create_time`。
- 实体依赖驼峰自动转下划线（`map-underscore-to-camel-case: true`），**未见 `@TableField` 自定义映射**。
- 主键：`@TableId(type = IdType.AUTO)`（数据库自增），字段名 `id`。`id` 类型因表而异：`location`/`store_pushed_history` 用 `Long`，`monitor_config`/`task_exec_history`/`user` 用 `Integer`。
- 逻辑删除：`@TableLogic private Boolean deleted`，DDL 对应 `deleted tinyint(1) DEFAULT 0`。**注意**：`store_pushed_history`、`task_exec_history` 表无 `deleted` 字段（无逻辑删除）。
- 全局配置见 `application.yaml` `mybatis-plus.global-config.db-config`：`id-type: auto`、`logic-delete-field: deleted`、`logic-delete-value: 1`、`logic-not-delete-value: 0`。

---

## Common Mistakes

- **不要给 DTO/VO 加 `@TableName`/`@TableId`**——既有 `LocationDTO`/`LocationVO` 误带持久化注解，是技术债，新代码勿重复。
- mapper 名与实体名可不一致（`NotifyConfigMapper`→`MonitorConfigEntity`），新增时尽量保持一致以避免混淆。
- 事务：service 写操作加 `@Transactional(rollbackFor = Exception.class)`（见 `LocationServiceImpl`）。
- **`updateById` 会忽略 null 字段，不能用它"把某列设回 null"**（2026-07-16，task 07-16-addr-login-state-select）。MyBatis-Plus 默认 `FieldStrategy=NOT_NULL`，patch 实体只 set id + 一个 null 值字段时，生成的 SQL 没有 SET 子句 → `UPDATE tbl WHERE id=? AND deleted=0` 语法错。要置 null 必须用 `LambdaUpdateWrapper.set(Field, null)` 显式写：`mapper.update(null, new LambdaUpdateWrapper<XxxEntity>().eq(id).set(field, null))`。本次 `LoginStateService.bindLocation` 解绑（`location_id=null`）即此坑。
- JDBC URL 必须含 `allowPublicKeyRetrieval=true`，否则 MySQL 重启后 caching_sha2 缓存清空会导致连接失败（事故教训，见 `application.yaml`）。
- 跨表关联：本项目**不用**联表查询，用多次查询 + 内存组装。
- **当天去重模式**：定时/触发类任务要"同一业务对象一天只处理一次"时，用 `apply("DATE(create_time) = CURDATE()")` 配合 `lambdaQuery().eq(...).count() > 0` 判断。依赖表的 `create_time` 字段（MP 自动填充）。**防重键要选"会被执行后改写"的字段**，否则占位会永久挡后续命中（见下方 `grab_config.auto` 约定的"已消费"语义教训）。
- **MINIMUM_PAY 去重键含 promotionId（2026-07-16，task 07-16-min-pay-dedup-promoid）**：`MinimumPayService` 的 N 分钟窗去重键是 `(storeId, promotionId)`，**不是单独 storeId**。README 记载"同一门店 promotion_id 每天不一样"，若只按 storeId 去重，昨日活动的推送记录会在滑动窗内把今日同店的新活动（新 promotionId）误挡 → 新活动无法推送（事故 case：23:40 推 P1，00:00 新 P2 被误挡）。改回 storeId 会重新引入此 bug，勿动。去重走批量查询（`StorePushedHistoryService.findPushedWithinMinutes`，一次取本配置窗内已推 `(storeId, promotionId)` 内存比对，消除逐店单查 N+1），配覆盖索引 `idx_notify_time_store_promo (notify_config_id, create_time, store_id, promotion_id)`。`promotionId` 为 null 时去重键用 `"null"` 占位，避免与有值活动混淆。STORE_ACTIVITY（当天+命中即停用）、STORE_KEYWORD（**用户全局 N 分钟窗 + `(storeId, promotionId)` 键，2026-07-20，task 07-19-store-keyword-dedup-minutes**，机制与 MINIMUM_PAY 完全一致，`StoreTask.cleanupExpired` 开头按 `type != STORE_KEYWORD` 提前 return 以免误清 STORE_ACTIVITY 当天记录）的去重逻辑各不相同，勿混用。

---

## Convention: 监控配置自动抢单字段

`monitor_config` 新增两列（2026-07-15）：
- `auto_grab tinyint(1) DEFAULT 0`：命中后是否自动建抢单任务。
- `grab_login_state_id int NULL`：自动抢单所用登录态，逻辑外键指向 `login_state.id`（统一登录态池，抢单/霸王餐共用）。

`autoGrab=false` 时 `grab_login_state_id` 在 `MonitoryConfigServiceImpl.addUpdateConfig` 里强制置 null（避免脏数据）；`copyProperties(dto, entity)` 在置 null 之后执行，不会反向覆盖。仅在美团(type=1)活动命中时建任务（决策A，上游 `grabPromotionQuota` 的 `store_platform` 写死 1）。

---

## Convention: grab_config.auto 标记 + 立即抢异步直连 + 活动快照

`grab_config` 新增 5 列（2026-07-15，task 07-15-monitor-grab-fix-and-display）：
- `auto tinyint(1) DEFAULT 0`：1=监控自动抢单(立即抢)产生的占位，**不进前端列表、不注册 cron**；0=手动/定时抢任务，正常展示。
- `store_name` / `promo_detail` / `start_time` / `end_time`：建任务时的**活动快照**（来自 `StoreInfo`），供抢单页展示，避免历史任务因 `promotion_id` 每日变化而查不到活动信息。

### Gotcha: GrabCronScheduler 的 executeAt 必须**严格在未来**

`GrabCronScheduler.schedule` 对一次性任务要求 `executeAt.minus(leadMs)` 转的 Instant `isAfter(Instant.now())`，否则记「executeAt 已过期，跳过」直接不调度。

> **Warning**: 不要用 `executeAt = LocalDateTime.now()` 表达"立即执行"——从 `save` 到 `refresh` 之间已过几毫秒，executeAt 就成了过去时刻，会被判过期跳过 → **任务建了永不抢**。且这条 ENABLE 僵尸会持续命中当天去重，把后续命中也永久挡住。

"立即执行"的正确做法是**不走 cron**：落 `auto=1` 占位后异步直接 `grabService.doGrab(config, "AUTO")`（独立 `ExecutorService`，避免阻塞 monitor-cron 线程），不调 `GrabCronScheduler.refresh`。

### 防重键用"已消费"字段（lastGrabTime），不要用启动前就置非空的字段

立即抢占位落库时会先置 `last_result="执行中"` 作状态展示。若防重条件含 `last_result IS NULL`，则该占位因 `last_result` 非空而**查不到** → 二次命中会再建占位重复抢。

正确：防重只看 `last_grab_time IS NULL`（执行前为空 → 挡二次命中；`doGrab` 成功内部写 lastGrabTime，失败/异常回调补写 lastGrabTime → 放行后续当天命中）。占位在**所有三种 doGrab 结局**（成功/失败/异常）下都要写 lastGrabTime，否则会永久挡。

### SINGLE 模式必须有"活动级"成功防重，账号级防重挡不住换号（2026-07-17 事故）

监控自动抢单 `GrabMode=SINGLE`（抢一个名额）下，`hasPlaceholder` 的防重键带 `login_state_id`，是**账号级**防重：只挡"同一账号重复抢同一活动"。但 SINGLE 的换号逻辑（`shouldSwitchAccount` 对 code==70/饭票不足/登录态过期返回 true）会换到下一账号继续抢**同一活动**。

账号级防重在成功后**失效**：account=1 抢成功 → doGrab 回写 lastGrabTime + status=DISABLE → `hasPlaceholder` 查不到 account=1 占位（lastGrabTime 已非空）→ 下次命中重新让 account=1 试 → 撞上游 code=11/饭票不足 → 换号 account=2 → **抢成第 2 个名额**，违反 SINGLE 语义。实锤：2026-07-17 活动 118779162，两账号各抢一个名额。

**SINGLE 必须在抢之前做活动级成功预检**：查当天 `auto=1 AND status=DISABLE AND last_result LIKE '成功%'` 的 promotionId 集合，命中即整组跳过，不再换号补抢。用 `status=DISABLE` 双重锁定（成功才 DISABLE，失败保持 ENABLE）避免失败记录误判；`LIKE '成功%'` 兼容美团（`成功 orderId=..`）与饿了么/京东（`成功`）。

**ALL 模式不做此预检**：ALL 语义是每账号各抢一个名额，账号间独立不互挡，账号级（无）防重即正确。两种模式的防重粒度不同，勿在 ALL 上套活动级预检。

### Validation & Error Matrix
- `auto=1` 记录被 `GrabServiceImpl.listByUserId` 的 `.ne(auto, true)` 过滤，不返回给前端列表（AC3）。
- 手动建任务（`addUpdateConfig`）`auto` 默认 0/null，`BeanUtils.copyProperties(dto, entity)` 会把 DTO 的快照字段拷进 entity。
- 异步 `doGrab` 在无 HTTP 上下文的线程池执行，必须用 `config.getUserId()` / `config.getLoginStateId()` 显式取，不可 `getByCurrentRequest()`（见 `error-handling.md`）。

### Wrong vs Correct

#### Wrong（旧版 bug，2026-07-15 生产事故）
```java
LocalDateTime executeAt = now;            // 立即抢设 now
entity.setExecuteAt(executeAt);
grabService.save(entity);
grabCronScheduler.refresh(entity.getId()); // 判 now 已过期 → 跳过，永不抢
// 且防重 count 用 lastResult IS NULL，但占位已置"执行中" → 二次命中查不到 → 重复抢
```

#### Correct
```java
entity.setAuto(true); entity.setLastResult("执行中");
grabService.save(entity);                 // auto=1 占位
grabExecutor.submit(() -> {               // 独立线程池，不阻塞 monitor-cron
    var r = grabService.doGrab(latest, "AUTO");   // 不走 cron，直接执行
    if (r == null || !r.isSuccess())
        lambdaUpdate().set(lastGrabTime, now).update(); // 失败/异常补写"已消费"
});
// 防重：count auto=1 + 当天 + lastGrabTime IS NULL  → 挡二次命中
```

---

## Convention: 全局单份配置表 + Holder 静态工具类 + 保存即生效

**What**：站点级全局配置（代理 IP 池、商家名称黑名单）用"全局单份表 + 静态 Holder 工具类"模式，而非环境变量或每用户多行表。

**Why**：环境变量改一次要重启服务；运行时读 DB + 内存快照，前端设置页保存即生效无需重启。Holder 静态方法让监控/抢单等调用方零成本接入（无需注入 Bean、无需改方法签名）。

**表结构约定**：
- 表名 `xxx_config`（如 `proxy_config`、`merchant_blacklist_config`），主键 `id` 固定为 1（全局单份），自增。
- ServiceImpl 含 `ensureRow()`（synchronized，表空则用默认值插 id=1 行）做懒初始化。
- 标准 5 件套：`XxxConfig{Entity,Mapper,DTO,VO}` + `XxxConfigService`/`Impl`/`Controller`，Entity 带 `@TableLogic deleted`。

**Holder 契约**（`XxxHolder` 静态工具类，放 `http/` 或同级包，对齐 `ProxyHolder`）：
```java
static boolean isXxx(...)        // 供监控/抢单/HTTP 调用方零成本接入
static void invalidate()        // Service.updateConfig 落库后调用，清快照实现即时生效
static void loadCfg()            // 经 SpringContextUtil.getBean(XxxService.class).getEntity() 读 DB
```
- **5s 内存快照**（`CFG_TTL`）：`loadCfg()` 快照未过期直接复用，避免每次调用打 DB。
- **异常/容器未就绪回退"安全默认值"**：`getEntity()` 返回 null 或抛异常时写一个禁用占位快照（如 `enabled=false`），且**刷新 `cfgLoadedAt` 使 TTL 节流生效**——避免 DB 故障时每次调用都重试打 DB。占位 entity 从不落库，仅内存占位。
- **service 调用在锁外**：`loadCfg()` 取 entity 在 synchronized 块外执行，避免持锁时进入 Service 实例锁形成反向锁顺序；锁内仅写快照。
- **即时生效**：`updateConfig` 落库后调 `Holder.invalidate()`，清快照使下次请求用新值。

**匹配/解析逻辑收敛在 Holder 一处**：监控侧与抢单侧共用同一 `isXxx(...)`，避免两处实现漂移。如商家黑名单的关键字 AND/OR 解析在 `MerchantBlacklistHolder.parseRules` + `isBlacklisted` 一处。

**何时用**：站点级策略（全局生效、所有登录用户可读可改、无管理员分级）。每用户隔离配置则参考 `grab_config`/`notify_config` 的多行 + `user_id` 模式，不用本模式。

**Related**：`proxy_config`/`ProxyHolder`、`merchant_blacklist_config`/`MerchantBlacklistHolder`；`error-handling.md`「定时任务复用的方法不能依赖 HTTP 请求上下文」（Holder/service 显式参数，定时任务线程安全调用）。
