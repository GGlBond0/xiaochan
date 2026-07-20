# 功能完整性缺口审查报告（audit-feature-completeness）

> 审查时间 2026-07-20，只读源码未改。对照 README 更新记录 + 各模块声明功能，核功能闭环与缺口。
> P0 完全缺失 | P1 半实现/空实现/字段未验证致不可用 | P2 缺口/不一致 | P3 遗留/冗余

---

## 功能缺口清单（按严重度）

### P1

#### F-A `XiaoChanController.apply/book/ignore` 空实现，前端在用
- 后端：`XiaoChanController.java:39-41,49-51,60-62` 三接口方法体仅 `return BaseResult.ok()`，入参全丢弃。
- 前端：`HomeView.vue:261` 调 `/api/xiaochan/apply/{promotionId}`，`:298` 调 `/api/xiaochan/ignore`。用户点「报名」「忽略」后端无效果却返回 ok → **前端误以为操作成功**。
- 性质：**半实现（空实现）**。早期「手动报名」形态遗留接口，当前核心路径是自动抢单。
- 处置建议：**需用户确认**——要么实现（若仍有报名需求），要么删前端入口+后端接口（若已弃用）。当前是「UI 在用、后端空转」的不一致状态。
- 归属：`controller/XiaoChanController` + 前端 `HomeView.vue`。

#### F-B README todo「以及再次通知」部分未实现
- README `todo` 末项 `- [ ] 以及再次通知` 未勾选。全仓 grep `再次通知/renotify/notifyAgain` 无任何相关代码。
- 现状（**部分实现**）：
  - MINIMUM_PAY / STORE_KEYWORD：已用「用户全局 N 分钟窗去重 + 过期清理」，N 分钟后同店新活动可再次通知 → **具备再次通知能力**。
  - STORE_ACTIVITY：`StoreTask.java:172` 命中后 `toggleStatus(DISABLE)` → **命中即停用，永不再通知**，无再次通知能力。
- 性质：**部分实现**。两类监控已具备，STORE_ACTIVITY 缺。
- 处置建议：确认 STORE_ACTIVITY 是否需要「再次通知」语义（当前是「指定门店活动提醒」一次性，可能设计如此）。若需要，参照 MINIMUM_PAY N 分钟窗改造。
- 归属：`tasks/StoreTask`（STORE_ACTIVITY 分支）。

#### F-C 饿了么/京东抢单请求体字段未验证（TODO 占位）
- `XiaochanHttp.buildOrderExchangeReq:319-367` 多个字段硬编样本值未按账号/活动验证：
  - `ingot(600)` 固定（:351 注释「来源未定位，样本占位，需按账号验证」）
  - `isSuperBrand(false)`（:358）、`bwcType(0)`（:362）、`storeCategoryType = subType`（:361）
- 性质：**半实现**——美团抢单已验证可用，饿了么/京东抢单请求体字段来源未完全定位，可能因字段值与真实账号不符被上游拒绝。
- 注释诚实标注 TODO，属已知遗留（07-17 多平台抢单任务）。
- 处置建议：抓包饿了么/京东真实抢单请求，定位 ingot/store_category_type 等字段真实来源后替换占位。
- 归属：`http/XiaochanHttp`。

### P2

#### F-D 代理 IP 池缺健康度监控
- 来自 prod-health F-3：代理全挂时 `BusinessException 代理不可用`（5次/天），重试3次后放弃。
- 现状：`ProxyHolder` 有失效换代理机制，但**无代理池健康度/可用 IP 数监控**，用户无法预知代理池将耗尽。
- 性质：**缺口**（功能存在但缺可观测性）。
- 归属：`http/ProxyHolder` + 前端代理配置页。

#### F-D-2 `LotteryServiceImpl` 异常吞掉返回 ok（状态不一致）
- 来自 code-quality C-14：`LotteryServiceImpl:86-160` 外层 catch 吞异常、`vo.setError()` 不抛出，`LotteryController` 返回 `BaseResult.ok(vo)`，code 仍 200。
- 前端需额外查 `br.result?.error` 才知失败，与其他接口 `success` 约定不一致。
- 性质：**前后端契约不一致**（功能闭环但状态信号偏差）。
- 归属：`service/impl/LotteryServiceImpl`。

### P3

#### F-E 前端 `/grab-login` 路由冗余
- `router/index.ts:33` `/grab-login` redirect 到 `/location`，但 `GrabLoginView.vue` 仍存在且可经 `/login-state` 访问。登录态统一池改造后的迁移期遗留。
- 性质：**冗余**。建议清理路由 + 视图。
- 归属：前端 `router` + `GrabLoginView.vue`。

#### F-F configId 5 cron 仅 00:00–00:30（待用户确认）
- 来自 prod-health O-2：`cron=0 0-30 0 * * *` 每天 00:00–00:30 每分钟一次。若用户期望全天监控需改 cron。
- 性质：**配置层，非代码缺口**。列为待用户确认项。
- 归属：用户配置（monitor_config 表）。

#### F-G configId 3（location_id=2）监控预期待确认
- 来自 prod-health F-1（已撤销 bug 判定）：minimumPay=3 阈值语义正确（实付差≤3），0 命中合理。但需确认用户对 location_id=2 的监控预期——该地点附近商圈是否真该有命中，或上游对该地点返回空。
- 诊断手段：在 `BaseTask.runSingle` fetchStoreInfos 后加上游返回量日志（一行）即可定位「上游无数据」vs「全被过滤」。
- 性质：**待诊断**（非缺口，是预期确认）。
- 归属：`tasks/BaseTask`（加日志属代码改动，另开任务）。

---

## 确认功能完整、闭环正常的模块

- ✅ **STORE_ACTIVITY 监控闭环**：`StoreTask` fetchStoreInfos(含 STORE_ACTIVITY) → filter(当天去重 checkRepeat:90) → 命中后停用(:172) → cleanupExpired 开头挡 STORE_ACTIVITY 不误清。完整。
- ✅ **STORE_KEYWORD 监控闭环**：N 分钟窗去重 + 过期清理（本会话已验证线上生效）。
- ✅ **MINIMUM_PAY 监控闭环**：N 分钟窗去重 + 过期清理（线上 50 次清理日志佐证）。
- ✅ **抢单三触发路径**：手动(executeManual)/cron(GrabCronScheduler)/一次性精确(ONESHOT, executeAt) —— `GrabServiceImpl.doGrab` + `AutoGrabServiceImpl.onScheduledFire` 闭环。
- ✅ **多账号多平台优先级轮询**：`AutoGrabServiceImpl` SINGLE/ALL 模式 + 平台优先级 + 账号换号降级 + 活动级成功防重。完整。
- ✅ **饭票校验**：`GrabServiceImpl.doGrab:201` 抢单前 countTicketByAuth，不足直接失败推送规避风控。完整。
- ✅ **霸王餐批量多账号**：前端 `SettingsView.vue:252-270` 多选账号串行逐个调 `/api/lottery/run`，后端 `LotteryServiceImpl.runTask` 单账号处理，批量汇总。完整（串行注释明确「禁止并发，小机1.7G」）。
- ✅ **登录态统一池**：`LoginStateServiceImpl` 抢单(GrabAuth)+霸王餐(LotteryAuth)共用、JWT 解析、归属校验、PUT location 绑定、过期提醒。完整。
- ✅ **推送按地址路由**：`PushServiceImpl` pushToLocation→getPushTargets(spt 列表)→兜底 user.spt→sendOne。闭环（sendOne 静默吞异常属 code-quality C-18，非功能缺口）。
- ✅ **商家黑名单 / 3km 筛选 / 距离排序**：前后端一致（`MerchantBlacklistHolder` + 监控/抢单两处过滤；StoreTask/MinimumPayService within3km；XiaoChanServiceImpl 距离排序）。
- ✅ **LotteryService 任务边界明确**：`FLAG_TO_TYPE` 注释明确 is_view_tp_ad/is_view_douyin_mall「纯接口刷不到」是已知边界，非遗漏。

---

## 跨 child 汇总输入（给 parent audit-findings.md）

- **F-A**（apply/book/ignore 空实现）：来自 code-quality C-13，本 child 确认前端在用，升级为「需用户确认实现或删除」。
- **F-B**（再次通知部分未实现）：README todo 明确项，STORE_ACTIVITY 缺。
- **F-C**（饿了么/京东抢单字段未验证）：07-17 任务已知遗留，TODO 标注。
- **F-D/D-2**（代理健康度 / Lottery 状态不一致）：分别来自 prod-health F-3、code-quality C-14。
- **F-G**（configId 3 诊断）：来自 prod-health F-1 撤销后的预期确认。

**整体结论**：项目核心功能（监控三类 + 抢单三路径 + 霸王餐批量 + 登录态统一池 + 推送路由）**闭环完整**，无 P0 完全缺失。主要缺口集中在：① 早期遗留接口空实现（F-A）；② README 明确 todo 未全实现（F-B STORE_ACTIVITY 再次通知）；③ 多平台抢单字段未完全验证（F-C）。均为「半实现/遗留」而非「完全缺」。
