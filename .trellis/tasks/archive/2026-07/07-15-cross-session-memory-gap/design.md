# Design — 跨会话长效记忆缺口补齐

## 边界

本任务只动三类**配置/记忆文件**，不碰代码、不碰部署、不重启服务：
1. 仓库根 `CLAUDE.md`（新建）
2. auto-memory 目录 `C:\Users\123\.claude\projects\C--D-AI-Projects-xiaocan-xiaocan-main\memory\*.md`（新增 3 条 + 更新 MEMORY.md）
3. Trellis 任务产物（本任务自身）

## 双通道长效记忆设计（核心模型）

把长效事实显式分两类，分通道注入，避免"知识堆进 CLAUDE.md 变长难读"与"指令只放记忆不被开局注入"两个陷阱：

| 通道 | 内容类型 | 注入时机 | 载体 | 写法 |
|---|---|---|---|---|
| A. 常驻指令 | 行为约束（"该怎么做"） | SessionStart 自动注入 | 项目根 `CLAUDE.md` | 祈使句，每条 ≤2 行，`[[记忆名]]` 引向细节 |
| B. 按需事实 | 知识/路径/状态（"是什么/在哪"） | AI 主动 recall | auto-memory `*.md` | 完整事实 + Why/How |

**分工原则**：CLAUDE.md 只回答"该不该/怎么做"，记忆回答"具体在哪/是什么"。CLAUDE.md 不写绝对路径、不写密码、不写服务清单——这些在记忆里，靠 `[[记忆名]]` 链接 recall。

## CLAUDE.md 结构

5 条行为指令分组（运行期/验证/构建/工具/部署），每条格式：`- **指令**：做法。→ [[记忆名]]`。顶部一行定位，底部一行"细节查 [[memory]]"。控制 ≤80 行。

## 新增记忆设计

### ssh-first-behavior（feedback）
- Why：默认工作目录=本地仓库导致路径依赖，反复在本地找运行产物落空。
- How：日志/产物/接口返回/systemd → 先 `ssh root@121.91.175.192`；源码/配置/git → 本地。
- 与 `runtime-logs-on-server` 互补：后者讲"日志在哪"，本条讲"行为上先去远端"。

### local-toolchain-inventory（reference）
- 本机已装工具全景表：JDK17（路径）、Maven 3.9.16（路径）、browser-relay（验活 curl）、mitmproxy（MCP 现状：mitmproxy-mcp 已注册且本会话函数列表可见）、gh CLI（`C:\Program Files\GitHub CLI\gh.exe`，未装则 winget）。
- 明确与 `local-build-toolchain` 分工：本条是"全景清单"，编译命令细节仍指向后者。

### browser-relay-mcp-status（reference）
- 现状：relay 进程在跑（实测 connected:true），`~/.claude.json` mcpServers 有 browser-relay，但本会话**无 `browser_*` 原生工具**。
- 根因：MCP 工具在会话启动时加载，中途注册需重启 Claude Code。
- 兜底：用 HTTP API `curl http://127.0.0.1:18795/api/*`（导航/截图/JS 等功能等价）。
- 补 `browser-relay-setup`（讲安装/自启）未覆盖的"当前会话工具可用性"维度。

## MEMORY.md 更新

追加 3 行（ssh-first / toolchain-inventory / relay-mcp-status），描述简短。不改动现有 14 行除非发现过时——本会话审阅未见过时。

## 兼容性 / 风险

- CLAUDE.md 是 SessionStart 注入内容的新增，不覆盖既有 Trellis SessionStart hook（hook 注入的是 Trellis workflow；CLAUDE.md 由 harness 原生注入，两者并存）。
- 不动 `~/.claude/CLAUDE.md`（用户级，保持空，避免全局污染其它项目）。
- 风险：CLAUDE.md 过长会稀释指令。缓解：硬约束 ≤80 行，只放行为指令。

## 验证方式

- 写完后 `cat` CLAUDE.md 计行数、核对 5 条指令与 `[[链接]]`。
- 新会话开局无法在本会话直接验证注入效果——以"文件存在 + 内容合规 + 链接指向存在的记忆"为验收，注入效果留作下次会话观察（PRD AC 第 5 条标注此约束）。
