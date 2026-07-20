# 代码质量与潜在 Bug 审查报告（audit-code-quality）

> 审查时间 2026-07-20，只读源码未改。后端 130 java / 9432 行，前端 20 vue/ts。
> 严重度：P0 主动 bug | P1 潜在 bug（边界/并发，特定条件触发）| P2 代码质量/可维护性 | P3 信息项

---

## 重要更正（对 prod-health 报告）

**F-1 撤销**：prod-health 报告 F-1「configId 3（minimumPay=3）阈值低于 id=4 却 0 命中，反常」的判断**错误**。

复核 `MinimumPayService.filterStoreInfos:68`：
```java
storeInfo.getPrice().subtract(storeInfo.getRebatePrice()).compareTo(extNotifyConfig.getMinimumPay()) <= 0
```
语义是「`price - rebatePrice <= minimumPay`」，即**实付差 ≤ 阈值**才命中。阈值越小**越严格**（要求实付差≤3 才命中，比≤5 更难）。故 configId 3（阈值3）比 configId 4（阈值5）更难命中，3 全天 0 命中而 4 有命中**完全合理**，非 bug。

→ prod-health 的 F-1 应降级/撤销。configId 3 仅需「上游是否真有数据」的进一步确认（建议加上游返回量日志），但不存在过滤逻辑 bug。本条转 Child 2（功能完整性）确认用户对 location_id=2 的监控预期。

---

## 问题清单（按严重度）

### P1 — 潜在 bug

#### C-1 `XiaochanHttp.parsePromotion` Integer 拆箱 NPE（多处）
- `XiaochanHttp.java:518` `jsonObject.getInteger("meituan_status") == 1`
- `:528` `jsonObject.getInteger("eleme_status") == 1`
- `:544` `tpPromotion.getInteger("tp_status") == 1`
- 触发：上游某条 promotion 缺对应 status 字段时 `getInteger` 返回 null，`== 1` 拆箱 NPE → `parsePromotion` 抛异常 → `parseListBody:580` 冒泡 → 整批 fetchStoreInfos 失败 → 该轮监控「没有满足条件」或异常。
- 证据：fastjson2 `getInteger` 对缺失字段返回 null，`Integer == int` 触发自动拆箱。
- 建议：改 `Integer.valueOf(1).equals(jsonObject.getInteger("meituan_status"))` 或 `Objects.equals(getInteger(...), 1)`。
- 归属：`http/XiaochanHttp`。

#### C-2 `XiaochanHttp.parsePromotion` / `parseBodyToAddress` 链式 NPE
- `:503` `store.getString("name")` 前置 `store = jsonObject.getJSONObject("store")` 未判 null
- `:423` `jsonObject.getJSONObject("status").getInteger("code")` 未判 status null
- `:564` `checkResult` 同样链式
- 触发：上游返回缺 `store` / `status` 字段 → NPE。
- 归属：`http/XiaochanHttp`。

#### C-3 `XiaochanHttp.getStorePromotionDetail:168/191` `storeInfos.get(0)`
- 触发：`parsePromotion` 返回空 list（无任何平台命中）时 `IndexOutOfBoundsException`。
- 归属：`http/XiaochanHttp`。

#### C-4 `BaseTask.savePushedHistory` 在 `sendMessage` 之前
- `BaseTask.java:118` 先 `savePushedHistory`（写推送历史），后 `:121` `sendMessage`（发推送）。
- 触发：若 `sendMessage`/`pushService.pushToLocation` 失败（`:257` catch 只 log），历史已写 → 下次该活动在 N 分钟窗口内被去重跳过 → **推送失败的活动用户收不到，且窗口内不会重试**。
- 严重度 P1（推送失败 + 历史已写 = 该活动在 N 分钟内不再推送）。
- 取舍：这是「命中即记」的设计意图（避免重复推送），但推送失败不应写入历史。建议 sendMessage 成功后再写历史，或推送失败时回滚/标记该历史。
- 归属：`tasks/BaseTask`。

#### C-5 `AutoGrabServiceImpl.markConsumed` 静默吞异常
- `AutoGrabServiceImpl.java:440` `catch (Exception ignore) { /* 尽力而为 */ }`
- 触发：若 `lastGrabTime` 回写失败（DB 抖动），占位不会被标记已消费 → `hasPlaceholder:402` 下次同账号同活动被误判跳过 → **该账号对该活动永久跳过**。
- 建议：至少 `log.warn`，或失败时尝试重试一次；失败要可观测。
- 归属：`service/impl/AutoGrabServiceImpl`。

#### C-6 `AutoGrabServiceImpl` 占位「查询-插入」无并发保护
- `tryComboWithAccounts:182` `hasPlaceholder(...)` 查 + `:194` `grabService.save(entity)` 之间无锁/无唯一约束。
- 触发：`grabExecutor` 多线程下，同账号同活动若两次命中叠加（监控高频命中 + ALL 多账号），可能双存占位 → 两线程都 `doGrab` → **双抢**（同账号同活动抢出 2 个名额，违反防重意图）。
- 现状缓解：`runSingle` 同门店组单线程串行；ALL 模式同账号单线程；跨账号才并行。同账号同活动并发概率低，但 `tryCreateFromMonitor` 被 BaseTask 不同门店组调用时，同账号跨门店不冲突。实际并发面有限，P2。
- 建议：DB 加 `(user_id, promotion_id, login_state_id, date)` 唯一索引，或 save 前 saveOrUpdate。
- 归属：`service/impl/AutoGrabServiceImpl`。

### P2 — 代码质量 / 可维护性

#### C-7 `AutoGrabServiceImpl.grabExecutor = newCachedThreadPool()` 无上限
- `:74` 无界线程池。监控高频命中 + 多账号 ALL 模式可能累积线程。
- 现状：命中频次低，-Xmx256m 下风险有限。
- 建议：改 `newFixedThreadPool(N)` 或有界队列，避免极端场景线程爆。
- 归属：`service/impl/AutoGrabServiceImpl`。

#### C-8 `XiaochanHttp.buildOrderExchangeReq` 饿了么/京东请求体大量 TODO 占位字段
- `:351` `ingot(600)` 固定、`:358` `isSuperBrand(false)`、`:362` `bwcType(0)`、`:361` `storeCategoryType = subType`（注释标 TODO，来源未定位，样本占位）。
- 触发：饿了么/京东抢单可能因字段值与真实账号不符而被上游拒绝，但不会崩。
- 这是**功能完整性问题**（字段未按账号验证），记 P1 转 Child 2。代码层标 P2（注释已诚实标注 TODO）。
- 归属：`http/XiaochanHttp` → Child 2。

#### C-9 `GrabServiceImpl.resolveAuth` / `listCards` 死代码 `auth == null`
- `GrabServiceImpl.java:367` `if (auth == null || !auth.isComplete())` —— auth 前面已 `.build()`，永不为 null，`auth == null` 恒 false。
- 同 `:505`。
- 影响：无（逻辑正确，仅冗余判断）。P3。

### P3 — 信息项

#### C-10 `ProxyHolder.getProxy(force=true)` 失败时重复拉取代理 API
- 重试3次每次 force 都会跳过缓存重新 `fetchProxy`，短时多次打代理 API。
- 现状：代理 API 有 8s timeout，影响有限。P3。

#### C-11 `MerchantBlacklistHolder.loadCfg` 为 public
- `:99` 公开方法易被误调，但内部用了 synchronized 块，安全。P3。

#### C-12 `GrabServiceImpl.doGrab` 请求异常路径每次 attempt 都记 history
- `:285` maxRetry=3 全异常 → 3 条 history。可接受，便于排查。P3。

---

## 确认正常 / 设计良好的模块

- ✅ `ProxyHolder`：volatile + 短锁 + 锁外 HTTP/service，避免死锁，兜底哲学清晰。
- ✅ `MerchantBlacklistHolder`：规则解析 + TTL 快照 + 禁用占位兜底，设计稳健。
- ✅ `MonitorCronScheduler`：ConcurrentHashMap 管理调度、cancel 重排、execute 前重查最新配置。
- ✅ `LoginStateServiceImpl`：JWT 解析、归属校验、`updateById` 忽略 null 坑已用 `LambdaUpdateWrapper.set` 规避（`bindLocation`）。
- ✅ `MinimumPayService` / `StoreTask`：N 分钟窗去重一次性取集合，无 N+1，机制一致。
- ✅ `GrabServiceImpl.doGrab`：饭票校验放行不误杀、活动详情循环外只查一次、重试/换号逻辑清晰。
- ✅ `AutoGrabServiceImpl` SINGLE/ALL 模式 + 活动级成功防重 + 到点回调，整体设计完整。

---

## F-1 根因结论（对应 prod-health）

configId 3 全天 0 命中 = **数据/预期问题，非代码 bug**。
- `filterStoreInfos:68` 过滤逻辑正确（实付差 ≤ minimumPay 才命中）。
- configId 3（location_id=2, minimumPay=3）阈值更严，location_id=2 附近商圈可能确无实付差≤3 的活动，或上游对地点2返回空。
- 最小诊断建议：在 `BaseTask.runSingle` 的 `fetchStoreInfos` 后打 `log.info("configId: {} 上游返回 {} 条", id, storeInfos.size())`，区分「上游无数据」vs「全被过滤」。一行日志即可定位。
- 后续：转 Child 2 确认用户对 location_id=2 的监控预期；加日志属代码改动，另开任务。

---

## 补扫结果（controller / lottery / 剩余 service / 前端）

### P1（新增，需优先修复）

#### C-13 `XiaoChanController.apply/book/ignore` 三个接口空实现
- `XiaoChanController.java:39-41,49-51,60-62` —— 方法体仅 `return BaseResult.ok()`，入参全丢弃。
- 触发：前端 `HomeView.vue` 调 `/api/xiaochan/apply/{promotionId}` 与 `/api/xiaochan/ignore`，**后端无任何效果**，用户「报名」「忽略」操作静默失败。
- 严重度：P1（功能性 bug，非质量问题）。
- 归属：`controller/XiaoChanController` → **转 Child 2 功能完整性**（需确认这些功能是否该实现，还是历史遗留接口）。

#### C-14 `LotteryServiceImpl.runLottery` 外层 catch 吞所有异常返回 ok
- `LotteryServiceImpl.java:86-160` —— `catch (Exception e) { vo.setError(...) }` 不抛出，`LotteryController` 返回 `BaseResult.ok(vo)`。
- 触发：lotteryInfo 全失败/代理全挂时，前端按 `response.data.success` 误判为成功（code 仍 200）。前端需额外查 `br.result?.error` 才知失败，与其他接口约定不一致。
- 严重度：P1（状态不一致，用户误以为刷任务成功）。
- 归属：`service/impl/LotteryServiceImpl`。

#### C-15 `MonitoryConfigServiceImpl.addUpdateConfig` 无 @Transactional
- `MonitoryConfigServiceImpl.java:176` —— `saveOrUpdate(entity)` 后紧跟 `monitorCronScheduler.refresh(entity.getId())`，无事务。
- 触发：refresh 抛异常时 DB 已提交无法回滚 → **配置存了但调度未刷新**，状态不一致。
- 同类：`:192` deleteById、`:201` deleteByLocationId、`:213` toggleStatus 均 `remove/cancel` 两步无事务。
- 严重度：P1（配置与调度不一致）。
- 归属：`service/impl/MonitoryConfigServiceImpl`。

#### C-16 前端 `App.vue` 硬编码 token 强制覆盖 + 无路由守卫
- `App.vue:7-10` —— `FIXED_TOKEN = '3fbd08ad2d164330'` 强制写入 localStorage，`if (localStorage.getItem('token') !== FIXED_TOKEN)` 覆盖用户手动输入的 token；注释「勿提交公开仓库」但**已提交**，token 泄露。
- `router/index.ts:1-58` —— 无任何路由守卫，未登录可直接访问任意页。
- `NavBar.vue:65-71` —— `extractAndStoreToken` 从 URL `?token=xxx` 写 localStorage **不校验有效性**，钓鱼链接可注入任意 token。
- 严重度：P1（自用项目风险可控，但多用户/部署实例会串号；token 泄露）。
- 归属：前端 `App.vue` / `router` / `NavBar.vue`。

### P2（新增）

#### C-17 `XiaoChanServiceImpl` NPE 隐患
- `:63` `queryListVO.getOrderType() != 1` —— Integer null 时 `null != 1` 为 true 进 needFullScan 分支，但 `sortStoreList` 内 null 走默认排序，行为不一致 + 性能浪费（null orderType 触发全量扫描）。
- `:144` `list.sort(Comparator.comparing(StoreInfo::getDistance))` —— distance 为 null NPE（orderType==4 分支用了 nullsLast，1 分支未用）。
- `:159` `storeInfo.getName().contains(...)` —— name 为 null NPE。
- 归属：`service/impl/XiaoChanServiceImpl`。

#### C-18 `PushServiceImpl.sendOne` 静默吞异常
- `PushServiceImpl.java:96-102` —— 推送失败只 log.error，调用方 `pushToLocation` 不抛异常。
- 触发：监控任务漏推但上层状态显示成功。与 C-4（savePushedHistory 在 sendMessage 前）叠加 = 推送失败用户彻底收不到且无重试。
- 严重度：P2（核心业务静默失败）。
- 归属：`service/impl/PushServiceImpl`。

#### C-19 全局配置 update 缺事务（ProxyConfig / MerchantBlacklist）
- `ProxyConfigServiceImpl.java:36-60` `updateById` + `ProxyHolder.invalidate()` 两步无事务。
- `MerchantBlacklistServiceImpl.java:35-46` 同。
- 触发：invalidate 失败则 DB 改了缓存没清。
- 归属：对应 service impl。

#### C-20 `UserServiceImpl.register` 无事务
- `UserServiceImpl.java:30-37` —— `lambdaQuery().oneOpt()` + `save(user)` 非原子，并发同 spt 注册写两条（DB 唯一索引兜底，代码层无保护）。
- 归属：`service/impl/UserServiceImpl`。

#### C-21 `LocationServiceImpl.delete` 调度副作用不回滚
- `LocationServiceImpl.java:52-70` —— `@Transactional` 但内部 `monitoryConfigService.deleteByLocationId` 的 `monitorCronScheduler.cancel` 副作用不随事务回滚 → 调度状态与 DB 不一致。
- 归属：`service/impl/LocationServiceImpl`。

#### C-22 `LotteryHttp.getNami` 长 id 越界
- `LotteryHttp.java:54-58` —— `uuid.substring(4, 20 - id.length() - 4)`，id 长度 >12 时第二参数为负 → `StringIndexOutOfBoundsException`。silkId 通常短，但传非数字长串会崩。
- 归属：`http/LotteryHttp`。

#### C-23 前端 `api/index.ts` code/success 双字段判断失配
- `api/index.ts:28-37` —— 拦截器用 `data.code`，业务代码各处用 `response.data.success`，两套判断；若 BaseResult 不同时含两字段，拦截器业务错误分支永不触发 → 漏处静默成功。
- `:42-44` —— 401 时未清 localStorage token 也未跳登录。
- 归属：前端 `api/index.ts`。

#### C-24 前端 `HomeView.vue` 异步错配
- `:624-631` `onMounted` 内 `loadDefaultAddress()` 未 await，`initScrollObserver()` 先执行绑定到不存在元素 → 首次加载不触发翻页（靠 handleSearch finally 兜底）。
- `:347-358` `getCurrentLocation` 赋值 lat/lng 后不触发 handleSearch，定位结果下次搜索才生效，用户感知错位。
- 归属：前端 `HomeView.vue`。

#### C-25 前端 `SettingsView.handleRun` 批量串行无取消
- `SettingsView.vue:255-283` —— 批量刷任务串行 for-await，单账号 30s 时整体很长，无超时/取消，用户无法中途停止。
- 归属：前端 `SettingsView.vue`。

#### C-26 `StorePushedHistoryServiceImpl` 重复查 user
- `:28-30` —— `userService.getByCurrentRequest()` 调两次（getId + getNotifyDedupMinutes 各一次），每次解析 token 查 DB。应缓存 user 对象。
- 归属：`service/impl/StorePushedHistoryServiceImpl`。

### P3（新增，精选）

- `LocationController.java:32` `new XiaochanHttp()` 直接 new 而非注入，与 XiaoChanServiceImpl 多实例，代理缓存不一致。
- `MonitoryConfigServiceImpl.java:56` `parseObject(extConfig,...)` extConfig null/非法 JSON 抛异常未 catch，listByUserId 整列表查询失败。
- `ProxyConfigServiceImpl.java:71` / `MerchantBlacklistServiceImpl.java:57` `ensureRow` synchronized 仅本实例有效，多实例部署失效。
- `TaskExecHistoryServiceImpl.java:23` `new Page<>(pageNum, pageSize)` 无默认值兜底，null 时 NPE。
- `XiaoChanServiceImpl.java:92` 硬编码 100 次上限无日志告警，极端静默截断。
- 各 controller 缺 `@Valid`/`@NotNull` 校验（LocationController.searchAddress、UserController.register、LotteryController.runTask 等）。
- 前端死代码：`HomeView.vue:132` loadedPages、`:339` canApply 未使用。
- 前端 `LocationView.vue:56` 循环内未 await 异步，N 地址并发 N 请求无限制。

### 补扫确认正常的模块

- ✅ `GrabHistoryView.vue` / `GrabLoginView.vue` / `LoginStateView.vue` / `GrabConfigView.vue` —— 简单列表/表单，await authState + load，无显著问题。
- ✅ `NotifyHistoryView.vue` catch 静默是设计意图。
- ✅ `LotteryServiceImpl.getLotteryCount` 已判空。

---

## 全报告汇总（P1 清单）

| ID | 位置 | 问题 | 归属/流转 |
|---|---|---|---|
| C-1 | `XiaochanHttp:518,528,544` | Integer 拆箱 NPE（status==1） | http/XiaochanHttp |
| C-2 | `XiaochanHttp:503,423,564` | 链式 NPE（store/status null） | http/XiaochanHttp |
| C-3 | `XiaochanHttp:168,191` | get(0) IndexOutOfBounds | http/XiaochanHttp |
| C-4 | `BaseTask:118` | savePushedHistory 在 sendMessage 前，推送失败=窗口内不重试 | tasks/BaseTask |
| C-5 | `AutoGrabServiceImpl:440` | markConsumed 静默吞异常，占位不标记→永久跳过 | AutoGrabServiceImpl |
| C-13 | `XiaoChanController:39-62` | apply/book/ignore 空实现 | → **Child 2** |
| C-14 | `LotteryServiceImpl:86-160` | catch 吞异常返回 ok，前端误判成功 | LotteryServiceImpl |
| C-15 | `MonitoryConfigServiceImpl:176` | addUpdateConfig 无事务，配置与调度不一致 | MonitoryConfigServiceImpl |
| C-16 | `App.vue:7` + `router` + `NavBar:65` | 硬编码 token 覆盖 + 无守卫 + URL token 不校验 | 前端 |

**主要风险集中三块**：① 事务缺失（MonitoryConfigServiceImpl 尤为突出，配置↔调度一致性）；② 异常吞掉致状态不一致（LotteryServiceImpl / PushServiceImpl / AutoGrabServiceImpl.markConsumed）；③ 前端 token/守卫缺失 + XiaochanHttp NPE。

**对 prod-health 的修正**：F-1（configId 3 0命中）撤销为 bug 判定，改为数据/预期问题（阈值语义误读），转 Child 2。
