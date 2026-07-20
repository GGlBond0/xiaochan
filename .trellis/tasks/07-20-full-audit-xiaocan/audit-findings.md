# 全面审查小蚕项目 — 汇总问题清单（audit-findings.md）

> 三维度审查汇总：生产运行健康（audit-prod-health）/ 代码质量（audit-code-quality）/ 功能完整性（audit-feature-completeness）。
> 全程只读，未改任何代码/生产配置。三份子报告见各自 `audit-*.md`。
> 严重度：P0 主动/完全缺 | P1 潜在/半实现 | P2 质量/缺口 | P3 信息/冗余。
> 去重规则：同一问题多维度命中只保留一条，标注来源 [prod]/[code]/[feature]。

---

## P1 — 需优先处理（9 项）

| ID | 问题 | 位置 | 来源 | 性质 | 建议归属 |
|---|---|---|---|---|---|
| A-1 | `XiaochanHttp` Integer 拆箱 NPE：`getInteger("meituan_status")==1` 等缺字段拆箱 NPE，整批 fetchStoreInfos 失败 | `XiaochanHttp.java:518,528,544` | [code] C-1 | 潜在 bug | http/XiaochanHttp |
| A-2 | `XiaochanHttp` 链式 NPE：`store`/`status` 未判 null 即取字段 | `XiaochanHttp.java:503,423,564` | [code] C-2 | 潜在 bug | http/XiaochanHttp |
| A-3 | `XiaochanHttp.getStorePromotionDetail` `storeInfos.get(0)` 空 list 越界 | `XiaochanHttp.java:168,191` | [code] C-3 | 潜在 bug | http/XiaochanHttp |
| A-4 | `BaseTask.savePushedHistory` 在 `sendMessage` 之前：推送失败则历史已写，该活动 N 分钟窗口内不再推送（用户收不到且无重试） | `BaseTask.java:118` | [code] C-4 | 潜在 bug | tasks/BaseTask |
| A-5 | `AutoGrabServiceImpl.markConsumed` 静默吞异常：回写失败则占位不标记→该账号对该活动永久跳过 | `AutoGrabServiceImpl.java:440` | [code] C-5 | 潜在 bug | AutoGrabServiceImpl |
| A-6 | `XiaoChanController.apply/book/ignore` 三接口空实现，前端在用，用户操作静默成功 | `XiaoChanController.java:39-62` + `HomeView.vue:261,298` | [code] C-13 / [feature] F-A | 半实现 | **需用户确认实现或删入口** |
| A-7 | `LotteryServiceImpl` 外层 catch 吞异常返回 ok，前端按 success 误判刷任务成功 | `LotteryServiceImpl.java:86-160` | [code] C-14 / [feature] F-D-2 | 状态不一致 | LotteryServiceImpl |
| A-8 | `MonitoryConfigServiceImpl.addUpdateConfig` 无 @Transactional，saveOrUpdate+refresh 非原子，配置存了调度未刷新（deleteById/deleteByLocationId/toggleStatus 同病） | `MonitoryConfigServiceImpl.java:176,192,201,213` | [code] C-15 | 配置↔调度不一致 | MonitoryConfigServiceImpl |
| A-9 | 前端硬编码 token 强制覆盖 localStorage + 无路由守卫 + URL `?token=` 不校验 | `App.vue:7` + `router/index.ts` + `NavBar.vue:65` | [code] C-16 | 自用可控但多用户串号/泄露 | 前端 |

---

## P2 — 建议处理（10 项）

| ID | 问题 | 位置 | 来源 | 建议归属 |
|---|---|---|---|---|
| B-1 | configId 3（MINIMUM_PAY, minimumPay=3, location=2）全天 258 次 0 命中 | （线上日志） | [prod] F-1 | **已撤销 bug 判定**：阈值语义误读，3≤3 比 4≤5 更严。待诊断上游返回量 + 用户预期 |
| B-2 | 上游「状态码 -1」间歇不可达 9次/天，本轮监控失败 | `XiaochanHttp:78` | [prod] F-2 | http/XiaochanHttp：区分 -1 vs 403 |
| B-3 | 代理全挂 BusinessException 5次/天，重试3次后放弃，缺健康度监控 | `ProxyHolder:173` | [prod] F-3 / [feature] F-D | ProxyHolder + 前端 |
| B-4 | `XiaoChanServiceImpl` NPE：`orderType != 1` null 误入全量扫描；`getDistance()` null NPE；`getName().contains()` null NPE | `XiaoChanServiceImpl.java:63,144,159` | [code] C-17 | XiaoChanServiceImpl |
| B-5 | `PushServiceImpl.sendOne` 静默吞推送异常，监控漏推但上层显示成功 | `PushServiceImpl.java:96` | [code] C-18 | PushServiceImpl |
| B-6 | 全局配置 update 无事务（ProxyConfig/MerchantBlacklist：updateById+invalidate 两步） | `ProxyConfigServiceImpl:36` / `MerchantBlacklistServiceImpl:35` | [code] C-19 | 对应 service |
| B-7 | `UserServiceImpl.register` 无事务，并发同 spt 注册写两条 | `UserServiceImpl.java:30` | [code] C-20 | UserServiceImpl |
| B-8 | `LocationServiceImpl.delete` 调度副作用(cancel)不随事务回滚 | `LocationServiceImpl.java:52` | [code] C-21 | LocationServiceImpl |
| B-9 | `LotteryHttp.getNami` id 长度>12 时 substring 越界 | `LotteryHttp.java:54` | [code] C-22 | LotteryHttp |
| B-10 | README todo「以及再次通知」STORE_ACTIVITY 未实现（命中即停用，永不再通知）；MINIMUM_PAY/STORE_KEYWORD 已有 N 分钟窗再次通知 | `StoreTask.java:172` | [feature] F-B | StoreTask（确认是否需改造） |

---

## P3 — 信息/冗余/优化（精选）

| ID | 问题 | 位置 | 来源 |
|---|---|---|---|
| C-1 | configId 5 cron 仅 00:00–00:30 跑（配置本身如此，非 bug） | monitor_config id=5 | [prod] O-2 / [feature] F-F |
| C-2 | RSS 286M 略超 Xmx256m（堆外，稳定无 OOM） | systemd | [prod] O-3 |
| C-3 | 饿了么/京东抢单请求体字段 TODO 占位未按账号验证（ingot/store_category_type 等） | `XiaochanHttp:319-367` | [feature] F-C / [code] C-8 |
| C-4 | 前端 `/grab-login` 路由 + GrabLoginView 冗余 | `router/index.ts:33` | [feature] F-E |
| C-5 | `AutoGrabServiceImpl.grabExecutor` newCachedThreadPool 无上限 | `AutoGrabServiceImpl.java:74` | [code] C-7 |
| C-6 | `StorePushedHistoryServiceImpl` 重复查 user 两次 | `:28-30` | [code] C-26 |
| C-7 | `ensureRow` synchronized 仅本实例有效，多实例失效 | ProxyConfig/MerchantBlacklist | [code] |
| C-8 | 各 controller 缺 @Valid/@NotNull 校验 | 多处 | [code] |
| C-9 | 前端 `HomeView` onMounted 未 await / 定位不触发搜索；`SettingsView` 批量串行无取消 | HomeView:624,347 / SettingsView:255 | [code] C-24/C-25 |
| C-10 | 当天无抢单/抽奖触发，链路健康无法凭日志确认（需手动触发验证） | （线上） | [prod] |

---

## 确认正常 / 设计良好的模块（避免「处处有 bug」错觉）

**生产**：systemd 稳定（07-17 重启为 SIGTERM 人为部署，非崩溃）、前端8088/后端10234/nginx、HikariCP、磁盘、STORE_KEYWORD & MINIMUM_PAY 新去重机制线上生效、代理重试机制工作、日志按天滚动。

**代码**：ProxyHolder、MerchantBlacklistHolder（volatile+短锁+锁外HTTP+兜底）、MonitorCronScheduler、LoginStateServiceImpl（JWT解析+归属校验+updateById null坑已规避）、GrabServiceImpl.doGrab、AutoGrabServiceImpl 主体、MinimumPayService+StoreTask 去重。

**功能**：监控三类闭环、抢单三触发路径、多账号多平台优先级轮询、饭票校验、霸王餐批量多账号、登录态统一池、推送按地址路由、商家黑名单/3km/距离排序。

---

## 整体结论

**项目健康度良好**：无 P0 完全缺失功能或主动崩溃 bug；生产稳定运行 1天18h 无崩溃；核心功能（监控+抢单+霸王餐+登录态+推送）闭环完整。

**主要风险三块**：
1. **事务缺失** → 配置↔调度/缓存一致性（MonitoryConfigServiceImpl 最突出，A-8）
2. **异常吞掉** → 状态不一致/静默失败（LotteryServiceImpl A-7、PushServiceImpl B-5、AutoGrabServiceImpl A-5、BaseTask A-4）
3. **前端 token/守卫 + XiaochanHttp NPE**（A-1/2/3、A-9）

**遗留/待确认**：
- A-6 apply/book/ignore 空实现 —— **需用户确认实现或删前端入口**
- B-1 configId 3 0 命中 —— 加上游返回量日志诊断 + 用户预期确认
- B-10 STORE_ACTIVITY「再次通知」—— 需用户确认是否要改造
- C-1 configId 5 cron 00:00–00:30 —— 需用户确认预期
- C-3 饿了么/京东抢单字段 —— 需抓包真实请求定位来源

---

## 后续修复任务建议（按优先级）

1. **A-1/2/3 XiaochanHttp NPE 集中修**（一批，低风险高收益）
2. **A-8 MonitoryConfigServiceImpl 事务补齐**（配置↔调度一致性）
3. **A-7/A-5/B-5 异常吞掉三处改可观测**（Lottery/Push/AutoGrab）
4. **A-4 BaseTask savePushedHistory 顺序**（推送失败不写历史或回滚）
5. **A-9 前端 token/守卫**（自用可控，优先级取决于是否多用户）
6. A-6 apply/book/ignore —— 待用户确认后决定
7. B-1/B-10/C-1/C-3 —— 待用户确认预期/抓包后决定

每条建议另开 Trellis 任务承接，本审查任务只诊断不修。
