# 技术设计：首页3km筛选与距离排序及监控3km条件

## 1. 边界与契约

### 1.1 后端 `QueryListVO`（`model/vo/QueryListVO.java`）增量字段
- 新增 `private Boolean within3km;` —— 是否仅看 3km 内（≤3000 米），默认 null/false。
- `orderType` 新增枚举值 `4`：按距离升序（无 distance 排末尾）。注释更新为 `1:默认 2:返现金额 3:返现比例 4:距离`。
- 向后兼容：字段可选，旧客户端不传时行为不变。

### 1.2 后端 `XiaoChanServiceImpl.query()` 路由调整
现状（L49-68）：
- `name` 非空 → searchList 路径。
- `orderType != 1` → 全量 `getList(MAX_SIZE)` + `sortStoreList` + `filter`。
- 否则（orderType==1/null）→ `getListByOffset` 官方分页，**不经过 filter/sort**。

问题：`within3km` 若在 orderType==1 时走分页路径，筛选不生效。

调整：将"是否走全量路径"的判断从 `orderType != 1` 改为 `orderType != 1 || Boolean.TRUE.equals(within3km)`。即：
- `name` 非空 → search 路径（不变）。
- `orderType != 1 || within3km == true` → 全量 `getList(500)` + `sortStoreList(orderType)` + `filter(含 within3km)`。
- 否则 → `getListByOffset` 分页（不变）。

这样 `within3km` 在任意 orderType 下都生效；距离排序 orderType=4 自然走全量路径。

### 1.3 后端 `sortStoreList` 增加距离排序分支
- `orderType == 4`：`list.sort(Comparator.comparing(StoreInfo::getDistance, Comparator.nullsLast(Comparator.naturalOrder())))`。
- 注意：orderType=1 在 sortStoreList 内本就按 distance 升序，但 query 路径下 orderType=1 不调用 sortStoreList（走分页），故无冲突。

### 1.4 后端 `filter` 增加 3km 过滤
- 在 `filter(list, queryListVO)` 末尾追加：
  ```
  if (Boolean.TRUE.equals(queryListVO.getWithin3km())) {
      list = list.stream().filter(s -> s.getDistance() != null && s.getDistance() <= DISTANCE_3KM).toList();
  }
  ```
- 新增常量 `private static final int DISTANCE_3KM = 3000;`（与现有 `MAX_DISTANCE=3500` 并存，语义不同：MAX_DISTANCE 用于翻页停止，DISTANCE_3KM 用于筛选）。
- `StoreInfo.distance` 为 null 的门店在 3km 筛选下被排除（合理：无距离信息无法判定）。

### 1.5 监控扩展配置增量字段
- `MinimumPayExtNotifyConfig`：新增 `private Boolean within3km = false;`（默认 false，向后兼容）。
- `StoreKeywordExtNotifyConfig`：新增 `private Boolean within3km = false;`。
  - 与已有 `limitDistance`（硬编码 ≤3500）**独立并存、AND 关系**：`within3km` 更严（≤3000），勾选后实际生效半径即 3km。保留 `limitDistance` 不动以向后兼容历史配置。
- `StoreExtNotifyConfig`（指定门店）：**不新增**，STORE_ACTIVITY 不出现该条件项。

### 1.6 监控任务过滤逻辑
- `MinimumPayService.filterStoreInfos`（L41-49）：在现有 filter 链中追加
  ```
  .filter(s -> !Boolean.TRUE.equals(extNotifyConfig.getWithin3km())
              || (s.getDistance() != null && s.getDistance() <= 3000))
  ```
- `StoreTask.filterStoreInfos` 的 STORE_KEYWORD 分支（L127-134）：在现有 `limitDistance` filter 之后追加同样的 `within3km` filter。
- 常量 3000：为集中管理，可在 `BaseTask` 或新增常量类定义 `DISTANCE_3KM = 3000`，两处引用。本次倾向在 `StoreTask`/`MinimumPayService` 内各用字面量 3000 并加注释，与现有 3500 字面量风格一致（现有代码也是硬编码 3500）。

### 1.7 前端 `HomeView.vue`
- `searchForm`（L19-28）新增 `within3km: false`。
- 排序 chip 区（L692-708）新增第 4 个 chip「距离」，`handleSort(4)`。
- 顶部切换区（L657-659 旁）新增「3km 内」开关，v-model `searchForm.within3km`，change 时 `handleSearch()`。
- `searchForm` 整体作为 `/api/xiaochan/query` body 提交（L145），新字段自动随上。
- 无需改 `formatDistance`/`refreshCurrentPageData`（后者已整体提交 searchForm）。

### 1.8 前端 `MonitorConfigView.vue`
- `form.minimumPayExtNotifyConfig` 新增 `within3km: false`。
- `form.storeKeywordExtNotifyConfig` 新增 `within3km: false`。
- 表单 UI：在 MINIMUM_PAY 与 STORE_KEYWORD 类型下显示「仅 3km 内」checkbox（复用现有条件区，与 `limitDistance` 开关并列展示于 STORE_KEYWORD；MINIMUM_PAY 在最小实付字段下新增）。
- `submitForm`（L298-348）按类型附加子配置时，`within3km` 随子配置对象一并提交（已包含在 form.xxx 对象内，无需额外处理）。
- 详情视图（L641-747）展示 `within3km` 状态。
- STORE_ACTIVITY 类型不渲染该 checkbox。

## 2. 数据流

### 首页查询
```
前端 searchForm(within3km, orderType, lat/lng...)
  → POST /api/xiaochan/query
  → XiaoChanServiceImpl.query()
     ├ name 非空 → searchList（上游搜索，结果不应用 within3km？见下文风险）
     ├ orderType!=1 || within3km → getList(500) + sortStoreList + filter(含3km)
     └ else → getListByOffset（上游分页，不应用 within3km）
```
**search 路径风险**：`name` 非空走 searchList，`query()` 中 search 分支不调用 `filter()`，故 `within3km` 在搜索时不生效。本次范围：3km 筛选仅在非搜索（浏览列表）场景生效；搜索场景保持上游返回。若需搜索也支持 3km，需对 search 分支也加 filter——列为可选增强，prd 未明确要求搜索下生效，**本次对 search 分支也应用 3km + 距离排序 filter**，以保证一致性（见 implement 第 6 项决策）。

### 监控任务
```
BaseTask.runSingle → fetchStoreInfos（上游拉取，带 location 经纬度）
  → filterStoreInfos（leftNumber>0 + 价格/关键字条件 + within3km + 未推送）
  → 命中则 savePushedHistory + 推送
```

## 3. 兼容性
- 所有新增字段可选/默认 false，旧数据、旧客户端不受影响。
- `ext_config` JSON 反序列化用 fastjson2，新增字段对历史 JSON（无该 key）反序列化为默认 false，向后兼容。
- 不改 DB schema（`monitor_config.ext_config` 是 text JSON，新字段无需建列）。
- 不改 `location` 表（默认地址仍前端 localStorage 记忆）。

## 4. 风险与权衡
- **3km 筛选下结果可能很少**：取决于用户默认地址周边门店密度。可接受，符合需求。
- **全量路径性能**：`within3km` 或距离排序触发 `getList(500)`，多页上游请求 + 内存排序过滤。与现有 orderType=2/3 路径开销一致，可接受。
- **上游 distance 缺失**：部分门店 distance 可能为 null，3km 筛选排除之，距离排序置末尾。
- **STORE_KEYWORD 双距离开关**：`limitDistance`(3500) 与 `within3km`(3000) 并存略显冗余，但为保证向后兼容不改动 `limitDistance` 语义；UI 上明确标注两者，3km 勾选后实际以 3km 为准。

## 5. 涉及文件清单
后端（xiaochan-main）：
- `src/main/java/io/github/xiaocan/model/vo/QueryListVO.java`
- `src/main/java/io/github/xiaocan/service/impl/XiaoChanServiceImpl.java`
- `src/main/java/io/github/xiaocan/model/MinimumPayExtNotifyConfig.java`
- `src/main/java/io/github/xiaocan/model/StoreKeywordExtNotifyConfig.java`
- `src/main/java/io/github/xiaocan/tasks/MinimumPayService.java`
- `src/main/java/io/github/xiaocan/tasks/StoreTask.java`

前端（xiaocan-front-main）：
- `src/views/HomeView.vue`
- `src/views/MonitorConfigView.vue`

## 6. 验证要点
- 后端单测/手测：orderType=4 升序 + nullsLast；within3km=true 仅返回 distance≤3000；within3km=false 行为不变。
- 前端手测：3km 开关 + 距离 chip 切换触发请求；监控表单勾选 3km 后提交 body 含 within3km=true。
- 部署后端 JAR 重启 + 前端 dist 替换，按 memory 部署拓扑执行。
