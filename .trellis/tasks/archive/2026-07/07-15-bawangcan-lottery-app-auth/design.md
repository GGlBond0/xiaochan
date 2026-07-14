# 技术设计：霸王餐抽奖刷任务改 App 版（Android 登录态）

> 需求见 `prd.md`，抓包证据见 `research/capture-app-lottery.md`。
> 路线 A：改造现有抽奖刷任务模块切到 Android 登录态；mini 模块完全删除；App 登录态新建独立表。

---

## 1. 总体方案

保留现有抽奖刷任务的"流程骨架"（`LotteryServiceImpl.runTask`：查未完成 → 逐个 AddLotteryTimes → 前后对比 lottery_count），只换三样东西：

1. **登录态来源**：`lottery_auth` 表 → 新的 App 登录态表（删除旧表，新建含 `sivir`/`city_code` 的表）。
2. **HTTP 层**：`LotteryHttp` 的 header 从 mini 切 Android、端点 `gw`→`gwh`、body 去 `app_id`。
3. **登录态 POJO**：`LotteryAuth` 加 `sivir` 字段，`isComplete()` 加 `sivir` 必填。

签名算法（`getAshe/getNami/generateUuid`）、代理/401/403 重试逻辑、`runTask` 流程结构、type 映射（2/8/9/10/11）**全部不变**。

## 2. 数据层

### 2.1 删除旧表，新建 App 登录态表

删除 `lottery_auth` 表 + `LotteryAuthEntity` + `LotteryAuthMapper` + DDL 条目。

新建表 `lottery_auth`（同名，但语义为 App 登录态；如担心脏数据，DDL 里 `DROP IF EXISTS` 重建）。字段：

```sql
DROP TABLE IF EXISTS `lottery_auth`;
CREATE TABLE `lottery_auth` (
  `id`          INT NOT NULL AUTO_INCREMENT,
  `user_id`     INT NOT NULL COMMENT '系统用户ID',
  `name`        VARCHAR(64) NOT NULL COMMENT '别名',
  `silk_id`     INT NOT NULL COMMENT '小蚕 silk_id(body + x-Teemo)',
  `user_vayne`  INT NULL DEFAULT NULL COMMENT '小蚕用户id(X-Vayne)',
  `session_id`  VARCHAR(64) NOT NULL COMMENT 'X-Session-Id',
  `sivir`       VARCHAR(512) NOT NULL COMMENT 'X-Sivir JWT(Android 登录态,必填)',
  `city_code`   INT NULL DEFAULT NULL COMMENT 'x-City 城市码,如 440111',
  `raw_headers` TEXT NULL COMMENT '原始抓包 header 留底',
  `create_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT(1) NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='小蚕霸王餐刷任务 App(Android) 登录态';
```

**字段变化（相对旧 mini 表）**：新增 `sivir`、`city_code`；**删除 `nami`**（App 版 X-Nami 每次请求随机生成，不存表）。`session_id` 保留（App 也有 X-Session-Id）。

### 2.2 Entity / Mapper

`LotteryAuthEntity`：删除 `nami` 字段，新增 `sivir`(String)、`cityCode`(Integer)，类注释改为 App 登录态来源说明。`@TableName("lottery_auth")` 不变。`LotteryAuthMapper` 不变。

## 3. HTTP 层（`LotteryHttp`）

### 3.1 端点
```java
private static final String BASE_URL = "https://gwh.xiaocantech.com/rpc"; // gw -> gwh
```
（抽奖三个接口抓包都在 `gwh`；`UserTaskV2` 等其它接口走 `gw`，但本模块不调，无需管。）

### 3.2 header：`getMiniHeaders` → `getAndroidHeaders`
```java
private Map<String,String> getAndroidHeaders(Long timeMillis, String ashe, String serverName,
                                             String methodName, String nami, LotteryAuth auth) {
    Map<String,String> h = new HashMap<>();
    h.put("servername", serverName);
    h.put("methodname", methodName);
    h.put("X-Ashe", ashe);
    h.put("X-Nami", nami);
    h.put("X-Garen", String.valueOf(timeMillis));
    h.put("X-Platform", "Android");          // was mini
    h.put("X-Version", "3.18.3.3");           // was 3.18.3.37
    h.put("x-Annie", "XC");                   // 新增
    h.put("X-Session-Id", auth.getSessionId());
    h.put("x-Teemo", String.valueOf(auth.getSilkId()));
    if (auth.getUserVayne() != null) h.put("X-Vayne", String.valueOf(auth.getUserVayne()));
    h.put("X-Sivir", auth.getSivir());        // 新增 JWT,必填
    if (auth.getCityCode() != null) h.put("x-City", String.valueOf(auth.getCityCode()));
    h.put("User-Agent", "XC;Android;3.18.3;");
    h.put("Content-Type", "application/json; charset=utf-8");
    return h;
}
```
**移除的 mini 专属 header**：`version`、`appid`、`xweb_xhr`、`X-Model`、`Referer`、小程序 `User-Agent`、`Accept`。

### 3.3 body：去 app_id
```java
private Map<String,Object> baseBody(LotteryAuth auth) {
    Map<String,Object> body = new HashMap<>();
    body.put("silk_id", auth.getSilkId());
    // body.put("app_id", APP_ID);  // 删除：App 版 body 无 app_id
    return body;
}
```
`APP_ID` 常量若仅 body 用则删除；header 不再用 appid。`addLotteryTimes` body 仍是 `{silk_id, type}`，与抓包一致。

### 3.4 Nami 生成
`getNami(silkId)` 沿用（silk_id 嵌入 16 位 hex）。App 抓包的 X-Nami 形如 `4f0222559356ab52`，正是该算法输出，无需改。

### 3.5 postAuth / executeWithProxy
`postAuth` 里 `getMiniHeaders` 调用名改为 `getAndroidHeaders`，其余（401 结构化失败、403 换代理、代理未启用直连）**完全不变**。

## 4. 登录态 POJO（`LotteryAuth`）

```java
@Data @Builder
public class LotteryAuth {
    private Integer silkId;
    private Integer userVayne;
    private String sessionId;
    private String sivir;      // 新增：Android JWT
    private Integer cityCode; // 新增：x-City
    private String nami;      // 保留(可选,默认随机),但 Entity 不存表 → 仅 POJO 字段,实际不读
    public boolean isComplete() {
        return silkId != null && sessionId != null && !sessionId.isEmpty()
            && sivir != null && !sivir.isEmpty();   // 加 sivir 必填
    }
}
```
> `nami` 字段保留可选（`postAuth` 兼容"读 Entity.nami 否则随机"逻辑），但 Entity 无 nami 列 → 恒走随机生成，行为正确。为干净起见也可删 `nami` 字段与相关分支，统一随机生成（推荐删，更清晰）。

## 5. Service 层（`LotteryServiceImpl`）

### 5.1 `saveAuth` 解析逻辑改动
解析抓包头新增提取：
- `X-Sivir` → `sivir`（必填，缺失报错"未解析到登录态：缺少 X-Sivir"）
- `x-City` / `X-City` → `cityCode`（可选，解析失败不报错）
- `x-Nami` 不再提取（Entity 无此列）
- 完整性校验：`silkId + sessionId + sivir` 三者必填。
- 同用户同 silk_id 去重逻辑不变。

### 5.2 `runTask`
`LotteryAuth` builder 新增 `.sivir(entity.getSivir()).cityCode(entity.getCityCode())`，去掉 `.nami(...)`。
`FLAG_TO_TYPE`（2/8/9/10/11）不变；在 `TYPE_TO_DESC` 注释里补一句"`is_view_tp_ad`/`is_view_douyin_mall` 在 App 端不走 AddLotteryTimes，纯接口刷不到，故不在此映射"。
`getLotteryCount` 沿用 `lottery_progress.lottery_count`（App 版响应多 first/second_step_count，不影响）。

### 5.3 其它
`listAuth`/`deleteAuth` 仅字段增减（VO 加 `sivir`? 一般不返回 JWT 到前端，**不返回 sivir**，VO 维持 id/name/silkId/userVayne/sessionId/updateTime，可加 `cityCode`）。

## 6. 前端（独立仓库 xiaocan-front-main，本任务我去改）

设置页"霸王餐抽奖"登录态录入区：
- 抓包头粘贴框 → 调 `/api/lottery/auth`（接口不变，DTO 字段不变，仅后端多解析 sivir/cityCode）。
- 列表/删除不变。
- 文案从"小程序登录态"改为"小蚕 App 登录态"，提示用户去小蚕 App 抓包（带 X-Sivir 的请求头）。
- 前端 DTO `LotteryAuthDTO` 字段：`rawHeaders`、`name`，无需前端解析，后端解析。

## 7. DTO / VO

- `LotteryAuthDTO`：不变（`rawHeaders` + `name`）。
- `LotteryAuthVO`：加 `cityCode`（可选），不加 `sivir`（JWT 不回前端）。

## 8. 删除清单（mini 模块完全删除）

- `ddl.sql` 里 `lottery_auth` DDL：替换为新 App 表 DDL（同名重建）。
- 代码层：**不删 `LotteryHttp`/`LotteryAuth`/`LotteryServiceImpl`/`LotteryController`**（它们被改造成 App 版，不是删）。删除的是"mini 语义"（mini header、app_id、nami 存表）。
- 旧 `LotteryAuthEntity.nami` 字段 + 列删除。
- spec `xiaocan-rpc-contract.md` 里 mini 抽奖登录态段落更新为 App 版（或并存说明历史）。

> 注：prd 说"完全删除 mini 模块"，但 `LotteryHttp` 等类本身是改造载体（切到 App 后就不是 mini 了），真正删除的是 mini 专属的数据（nami 列、app_id、mini header、mini 登录态表语义）。理解为"删掉 mini 那套数据与配置，类改造复用"。

## 9. 风险与验证

| 风险 | 应对 |
|---|---|
| `X-Sivir` JWT 过期（约 7 天） | `LotteryInfo`/`AddLotteryTimes` 返回 401 时提示用户重新录入；不重试。抓包 exp=1786147113≈2026-08-04 |
| `gwh` 端点对部分接口不可达 | 抓包确认三个抽奖接口都在 `gwh`，本模块只用这三个；若未来加 UserTaskV2 需走 `gw`，届时按接口分端点 |
| 分享(type=2)需 `CreateLeaderInviteWord` 前置 | 实现时先单测纯调 `AddLotteryTimes(2)`；若返回非 0，再补调 `CreateLeaderInviteWord` |
| 旧 `lottery_auth` 表有 mini 数据 | DDL `DROP IF EXISTS` 重建，旧数据清除；生产部署需提示用户重新录入 App 登录态 |
| 抢单核心被误改 | 不动 `GrabAuth`/`grab_login_state`/`XiaochanHttp`，新表与 `grab_login_state` 物理隔离 |

## 10. 回滚

- 后端：git revert 单 commit 回退；DDL 回退为旧 mini 表（但数据已丢，需用户重录）。
- 因 DDL 破坏性删表，部署前在测试库验证，生产部署时同步告知需重新录入登录态。

## 11. 不在本任务范围

- 执行抽奖（TCaptcha 滑块）。
- 刷 `is_view_tp_ad`/`is_view_douyin_mall`。
- 抢单/查询/卡券模块。
