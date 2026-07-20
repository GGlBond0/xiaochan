# 生产运行健康审查报告（audit-prod-health）

> 审查时间：2026-07-20 21:25–21:40 CST，只读 SSH + SELECT，未改任何生产配置/代码。
> 数据源：`/opt/xiaocan/logs/info.log`（当天，350KB）+ `error.log`（345 行）+ 历史天滚动日志 + systemd/journalctl + `monitor_config` 表 SELECT。

---

## 0. 概览

| 项 | 值 | 判定 |
|---|---|---|
| 服务 | `xiaocan.service` active running | 正常 |
| 运行时长 | 自 2026-07-19 03:19 起，1天18h+ | 正常 |
| NRestarts（当前周期） | 0 | 正常 |
| 历史 7d 重启 | 07-17 有 5 次 status=143(SIGTERM) + 07-19 1 次 | 见 §4，非崩溃 |
| 内存 RSS | 286M / Xmx256m | 观察项（见 §5） |
| 磁盘 /opt | 43% (22G avail) | 正常 |
| HikariCP | 无 leak/不可用日志 | 正常 |
| 前端 8088 首页 | 200 | 正常 |
| 后端 10234 | 200 | 正常 |
| nginx 配置 | test ok | 正常 |

线上活配置：`monitor_config` 中 id=3/4/5 ENABLE 且 deleted=0；id=6 软删（deleted=1）忽略。三配置均 `user_id=2`。

---

## 1. 各 configId 执行与健康

| id | type | cron | 近期执行(当天) | 结局分布 | 判定 |
|---|---|---|---|---|---|
| 3 | MINIMUM_PAY | `0 */5 * * * ?` 每5分钟 | 258 次 | 258×「没有满足条件」，0×「找到」 | ⚠ 观察项 O-1 |
| 4 | MINIMUM_PAY | `0 */10 * * * ?` 每10分钟 | 179 次 | 49×「找到N个」(1/2/3/5/6), 79×「没有满足」, 有推送 | ✅ 正常 |
| 5 | STORE_KEYWORD | `0 0-30 0 * * *` 每天00:00–00:30每分钟 | 31 次（仅00:00–00:30） | 2×「找到2个」并推送, 29×「没有满足」 | ✅ 行为符合配置 |

### O-1（P2 观察项）：configId 3 全天 258 次执行 0 命中
- 证据：`grep 'configId: 3' info.log` 全为 `没有满足条件的门店活动`，无一次「找到」。
- 反常点：configId 3 `minimumPay=3`（满减差≥3元通知），configId 4 `minimumPay=5`（阈值更严）反而 49 次命中。3 的阈值更低却从不命中，逻辑上反常。
- 但 3 与 4 `location_id` 不同（3→地点2，4→地点3），不同地址附近商圈不同，可能确无满减差≥3 的店。
- **未确证为 bug**：BaseTask 不打上游 `fetchStoreInfos` 返回量，无法区分「上游返回0条」vs「返回多条全被过滤」。需 Child 1（代码）补看 `MinimumPayService.filterStoreInfos` 是否存在误过滤，或上游对 location_id=2 的地点确实无数据。
- 严重度：P2（用户期望可能落空但不确定是代码问题）。
- 建议归属：MINIMUM_PAY 模块，转 Child 1 复核过滤逻辑 + 加上游返回量日志。

### 关于 configId 5 的「00:30 后停摆」
- 初判曾疑为 P0 静默失败。核实 DB：`cron=0 0-30 0 * * *`，即**每天仅 00:00–00:30 每分钟一次**，00:30 后不执行是配置本身如此，**非 bug**。
- 行为符合配置：00:22 推送成功（STORE_KEYWORD N 分钟窗去重新机制生效），00:29/00:30 后无日志因 cron 不再触发。
- 观察项（O-2，P3）：用户若期望该关键词全天监控，需改 cron 表达式。当前实现无问题。

---

## 2. error.log 异常归类（当天 345 行）

| 异常类型 | 次数 | 触发点 | 严重度 | 说明 |
|---|---|---|---|---|
| DelegatingError（Spring 调度包装） | 28 | 根因 `XiaochanHttp:78 状态码错误: -1, body: 空`，经 `MonitorCronScheduler.execute:121` 抛出被调度吞栈 | P2 | 上游小蚕网关间歇返回 -1（连接失败/被拦），见 §2.1 |
| BusinessException | 14 | `代理不可用，无法请求小蚕网关` | P2 | 代理 IP 全部超时后抛业务异常，自恢复 |
| SocketTimeoutException | 5 | `ProxyHolder:173 代理 API 请求异常: Connect timed out` | P3 | 代理偶发连不上，有重试机制（3 次）兜底 |
| IOException / handleIOException | 各 2 | 上游/客户端 IO | P3 | 零星 |
| ClientAbortException / AsyncRequestNotUsable | 各 2 | 客户端中断上传 | P3 | 非业务问题 |
| GlobalResultException / Multipart / FileUpload / MalformedStream | 各 1 | | P3 | 零星 |

### 2.1（P2）：上游「状态码 -1」间歇性不可达
- 频次：当天 9 次，时段 02/06/11/14/16/20 各 1–2 次；历史天（07-17）同模式。
- 模式：偶发、自恢复、非持续封禁。疑似上游小蚕网关/IP 池间歇 403 或连接重置。
- 触发后该次监控本轮失败，下一轮（5/10 分钟后）通常恢复。
- 建议归属：`http/XiaochanHttp` + 代理模块，转 Child 1 看 `executeWithProxy` 对 status=-1 的处理是否有改进空间（如区分 -1 vs 403，-1 可快速重试）。

### 2.2（P2）：代理全挂 → BusinessException「代理不可用」
- 频次：当天 5 次。代理重试 3 次全超时后抛。
- 现有重试逻辑存在（`XiaochanHttp:111 换代理重试 N/3`），机制正常。
- 建议：代理池健康度/可用 IP 数监控，转 Child 2 核「代理 IP 池」功能完整性。

---

## 3. 抢单 / 抽奖任务线上情况

- 当天日志中**未观察到抢单（GrabTask/GrabCronScheduler）或抽奖（LotteryService）执行记录**。`monitor_config` 三活配置均监控类（MINIMUM_PAY/STORE_KEYWORD），无 grab/lottery 专属配置触发记录。
- 这与 `auto_grab` 字段：id=3 auto_grab=1, id=5 auto_grab=1（监控命中后自动抢单）一致——抢单是监控命中的下游动作，非独立 cron。当天有命中的 id=4 auto_grab=0（不自动抢），id=3/5 无命中故未触发抢单。
- 判定：**无法仅凭日志确认抢单链路是否健康**（因当天无命中触发）。转 Child 1 核 GrabService/AutoGrabService 代码 + Child 2 核「抢单」功能闭环；建议后续手动触发一次有命中的监控以观察抢单链路实际行为。

---

## 4. systemd 稳定性 / 历史重启

- 07-17 02:35/02:43/02:53/05:17/20:02 共 5 次 + 07-19 03:19 1 次，全部 `Main process exited, code=exited, status=143/n/a`。
- **status=143 = SIGTERM（128+15）**：进程被外部 SIGTERM 终止，**非 OOM（应为 137）非未捕获异常崩溃**。
- 结论：07-17 的重启是**人为/脚本主动 `systemctl restart`** 触发（部署/调试），非服务自身崩溃。07-19 03:19 为本任务（store-keyword-dedup-minutes）jar 部署后的重启，与 git 提交时间一致。
- 判定：✅ 无崩溃性问题。当前周期稳定运行 1天18h。

---

## 5. 资源

- RSS 286M vs Xmx256m：堆上限 256M，RSS 286M 含堆外（DirectBuffer/元空间/线程栈）。稳定运行 1天18h 无 OOM、无 GC 风暴日志，**观察项 O-3（P3）**：堆外略超 Xmx 但未触发 kill，可加 `-XX:MaxMetaspaceSize`/监控，非紧急。
- 磁盘 43%、HikariCP 无异常、nginx 正常。

---

## 6. 线上行为 vs 声明偏差

- STORE_KEYWORD（id=5）N 分钟窗去重 + 过期清理：本次会话已 javap 核对线上 class 含 `cleanupExpired`/`findPushedWithinMinutes`，且 00:22 命中推送成功。**线上与 spec 一致**。
- MINIMUM_PAY N 分钟窗去重（id=3/4）：id=4 有 `configId: 4 清理 N 分钟前的过期推送记录 X 条`（50 次），与 spec 一致。
- STORE_ACTIVITY：活配置中无此类型，未观察到。
- 偏差：configId 3 全天0命中（O-1），与「MINIMUM_PAY 功能正常」的预期有偏差，待 Child 1 复核。

---

## 问题清单（按严重度）

| ID | 严重度 | 问题 | 证据 | 是否需修 | 建议归属 |
|---|---|---|---|---|---|
| F-1 | P2 | configId 3（MINIMUM_PAY, minimumPay=3, location=2）全天 258 次执行 0 命中，阈值低于 id=4 却无命中 | `grep 'configId: 3' info.log` 全为「没有满足条件」 | 需诊断 | → Child 1 复核 `MinimumPayService.filterStoreInfos` + 加上游返回量日志 |
| F-2 | P2 | 上游「状态码 -1」间歇性不可达（9次/天，散布全天），本轮监控失败 | `error.log: XiaochanHttp:78 状态码错误: -1` | 评估 | `http/XiaochanHttp`：区分 -1 vs 403，-1 可快速重试 |
| F-3 | P2 | 代理全挂时 BusinessException「代理不可用」（5次/天），重试 3 次后放弃 | `ProxyHolder:173` + `XiaoChanServiceImpl:130` | 评估 | 代理池健康度监控 → Child 2 |
| O-1 | P2 | 同 F-1（观察项表述） | — | 同 F-1 | — |
| O-2 | P3 | configId 5 cron 仅 00:00–00:30 执行，若期望全天监控需改 cron | DB `cron=0 0-30 0 * * *` | 视用户期望 | 配置层，非代码 |
| O-3 | P3 | RSS 286M 略超 Xmx256m（堆外），未 kill | `ps` + systemd | 观察 | 启动参数，非紧急 |
| — | P3 | 当天无抢单/抽奖触发记录，无法凭日志确认其链路健康 | info.log 无 Grab/Lottery 事件 | 需主动验证 | 手动触发命中后观察，转 Child 1/2 |

---

## 确认正常 / 无问题的模块

- ✅ systemd 服务稳定性（无崩溃，07-17 重启为人为 SIGTERM）
- ✅ 前端 8088 首页 + 后端 10234 + nginx 反代
- ✅ HikariCP 连接池（无泄漏/不可用）
- ✅ 磁盘空间
- ✅ STORE_KEYWORD（id=5）新去重机制线上生效
- ✅ MINIMUM_PAY（id=4）N 分钟窗去重 + 过期清理线上生效
- ✅ 代理重试机制（3 次换代理）存在且工作
- ✅ 日志按天滚动正常

---

## 给后续 child 的输入

- **→ Child 1（代码质量）**：① 复核 `MinimumPayService.filterStoreInfos` 是否对 location_id=2 / minimumPay=3 存在误过滤（F-1）；② `XiaochanHttp.executeWithProxy` 对 status=-1 的处理（F-2）；③ BaseTask 是否应在 `fetchStoreInfos` 后打上游返回量日志，便于区分「上游无数据」vs「全被过滤」（诊断 F-1 的根因手段）。
- **→ Child 2（功能完整性）**：① 代理 IP 池健康度/可用数监控是否存在（F-3）；② 抢单/抽奖链路因当天无命中未触发，需对照 README 核其闭环完整性；③ configId 5 cron 是否符合用户预期（O-2，产品层）。

---

## 附：取数命令（可复跑）

见 `design.md` / `implement.md`。全部只读：`grep`/`systemctl show`/`journalctl`/`mysql SELECT`/`curl`/`ps`/`df`。
