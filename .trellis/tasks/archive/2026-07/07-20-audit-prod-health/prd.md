# PRD: 生产运行健康审查（audit-prod-health）

## Goal / 用户价值

通过 SSH 读生产服务器（`121.91.175.192`）的运行日志、systemd 状态、nginx/前端可达性，核线上各任务是否真在跑、有无长期静默失败或异常被吞、服务是否稳定，产出一份带证据的生产健康问题清单。**只读不改**。

## 背景 / 已探明事实（2026-07-20 本次会话）

- 生产 jar：`/opt/xiaocan/xiaocan.jar`（Jul 19 03:17），systemd `xiaocan.service`，`NRestarts=0`，自 07-19 03:19 起运行至今。
- 内存：`-Xms128m -Xmx256m`，RSS≈286M（含堆外/元空间，略超 Xmx，需记观察项）。
- 日志：`/opt/xiaocan/logs/info.log`（当天）+ `info.YYYY-MM-DD.log`（按天滚动），error 同结构。当天 info≈350KB、error≈31KB/345 行，均可全读。
- 监控 cron 线程名形如 `monitor-cron-N`；已知 configId 4/5 在跑，5=STORE_KEYWORD（斑斓包点）07-20 00:22 推送成功，4 有清理日志。

## Requirements

- R1 统计**各 configId 的执行频次**：grep `configId:` 在当天 info.log 的出现次数 + 最后出现时间，找出「配置存在但长期无日志」的静默配置（可能 cron 未生效或被过滤吞掉）。
- R2 统计**各 configId 的「没有满足条件」vs「找到N个」vs「发送消息」分布**，识别长期只打「没有满足条件」的配置（命中逻辑坏、或上游接口变了、或全被去重过滤）。
- R3 扫 **error.log 全部 345 行**：按异常类型/栈归类统计，标出反复出现的异常（如反复 NPE、连接超时、HTTP 403/封禁、JSON 解析失败）及其触发 configId/任务。
- R4 核 **systemd 稳定性**：`NRestarts`、`ActiveEnterTimestamp`、近 7 天有无 OOM/重启痕迹（journalctl）。
- R5 核 **资源**：内存 RSS vs Xmx256m、HikariCP 连接池状态（日志中 `HikariPool` 相关）、磁盘 `/opt` 余量。
- R6 核 **前端可达性**：`curl` 生产域名/端口 8088 首页 200、`/api/` 反代 127.0.0.1:10234 通（GET 一个无鉴权接口如 `/api/debug` 或健康检查）。
- R7 核 **线上行为 vs spec 声明偏差**：抽取若干 configId 在 monitor_config 表的类型/cron/ext_config，与日志执行节奏对照，确认与 spec 描述一致。
- R8 产出 `audit-prod-health.md`：问题清单（configId/日志证据/严重度/触发条件/是否需修/建议归属）+ 明确列出「确认正常」的模块。

## 严重度定义

- **P0**：服务不可用 / 数据丢失 / 主动任务完全失效（如某 configId 长期不执行、线上 jar 损坏）。
- **P1**：反复异常被吞 / 关键功能静默失败（如某监控永远「没有满足条件」但应命中、error.log 高频同类异常）。
- **P2**：资源逼近上限 / 行为与声明偏差（内存接近 Xmx、cron 节奏与配置不符）。
- **P3**：观察项 / 信息性（无即时风险，记录待跟踪）。

## Acceptance Criteria

- [ ] `audit-prod-health.md` 产出，含 R1–R7 各小节证据。
- [ ] 每条问题带 `configId` 或日志行证据 + 严重度 + 触发条件 + 是否需修 + 建议归属模块。
- [ ] 列出「确认正常」的模块清单。
- [ ] 全程未改生产任何配置/代码；只 SELECT、只读日志、只 systemctl show/journalctl。

## Out of Scope

- 修复发现的问题（另开任务）。
- 改 cron 配置、改 monitor_config 数据、重启服务。
- 性能压测。

## Open Questions

无。
