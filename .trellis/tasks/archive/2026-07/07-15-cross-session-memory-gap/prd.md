# 跨会话长效记忆缺口补齐(CLAUDE.md+记忆)

## Goal

补齐 xiaocan 项目在跨会话维度丢失的"长效事实与行为约束"，让新会话一开局就能感知到：已装工具、SSH 远端操作、浏览器自动化、SSH-first 看日志等，避免每次会话重复踩坑（找不到工具、不自行测上线效果、不去服务器操作）。

## 背景与问题陈述

用户反馈：过往会话反复发生以下问题——
1. **不会自行控制浏览器测试上线后效果**：本机已装 browser-relay(18795) 且当前在跑（实测 connected:true, 14 tabs），但新会话不主动用它验证前端上线效果。
2. **找不到之前装过的工具**：JDK17/Maven/relay/mitmproxy 等装过即可用的工具，散落在少数记忆里，不成体系，新会话无从知道"已装/装在哪/怎么唤起"。
3. **不会 SSH 去真实部署的云服务器操作**：121.91.175.192 免密 SSH 已配好且实测通，但新会话默认在工作目录本地找运行产物，不主动去远端。
4. **跨会话记忆漏掉长效约束**：只有 auto-memory（被动 recall），没有每次会话主动注入的常驻指令（无 CLAUDE.md——项目根无、用户级 `~/.claude/CLAUDE.md` 为 0 字节空文件）。

根因：长效事实分两类——(A) 行为指令（"该怎么做"，需每次开局强提示）和 (B) 知识事实（"是什么/在哪"，按需 recall 即可）。当前两类都只靠 auto-memory 被动加载，缺 (A) 的常驻注入通道，且 (B) 有若干缺口。

## Requirements

### R1 创建项目根 CLAUDE.md（行为指令常驻注入）
- 在仓库根 `C:\D\AI\Projects\xiaocan\xiaocan-main\CLAUDE.md` 新建，写入**行为类**长效指令（精炼，不堆砌知识）：
  - SSH-first：涉及运行期日志/产物/接口返回/systemd，第一步 `ssh root@121.91.175.192` 去远端，不在本地仓库找。
  - 浏览器验证：前端上线/接口验证优先用 browser-relay（`curl http://127.0.0.1:18795/api/debug` 验活；无 `browser_*` 原生工具时用 HTTP API `curl /api/*` 兜底）。
  - 生产构建禁跑 mvn：永不 `ssh` 到生产服务器跑 mvn，本地构建或 Actions。
  - 工具链绝对路径：会话 PATH 不带 mvn/java，编译用绝对路径（见 local-build-toolchain 记忆）。
  - 部署步骤勿臆造：照记忆/实测，勿照任务文档臆造部署路径。
- 不在 CLAUDE.md 复述知识细节（路径/密码/服务清单），只写指令并 `[[记忆名]]` 指向对应 auto-memory 文件供按需 recall。

### R2 补齐缺失的 auto-memory 记忆
- 新增 `ssh-first-behavior`（feedback 类）：远端操作优先，含 Why/How to apply。
- 新增 `local-toolchain-inventory`（reference 类）：本机已装工具清单 + 唤起方式（JDK17/Maven 绝对路径、browser-relay 验活命令、mitmproxy MCP 现状、gh CLI 位置），作为 `local-build-toolchain` 的"工具全景"补充（后者聚焦 Java/Maven 编译）。
- 新增 `browser-relay-mcp-status`（reference 类）：当前 relay 在跑但会话内无 `browser_*` 原生工具的根因与兜底（HTTP API），补 `browser-relay-setup` 未覆盖的"工具可用性感知"层面。
- 审阅并更新 MEMORY.md 索引：追加新条目，并修正任何过时描述。

### R3 排查 browser-relay 原生工具缺失（诊断，非修复承诺）
- 确认 `~/.claude.json` mcpServers 有 `browser-relay`（已确认有），但本会话函数列表无 `browser_*`——诊断为"需重启 Claude Code 才加载 MCP 工具"（已知坑，记忆已记）。
- 不在本任务强修（重启会话超出任务边界），只在 CLAUDE.md/记忆里写明兜底路径与重启提示。

## Acceptance Criteria

- [ ] 仓库根 `CLAUDE.md` 存在且为行为指令（≤80 行），含 R1 五条指令，每条引用对应 `[[记忆名]]`。
- [ ] 新增 3 条 auto-memory 文件（ssh-first-behavior / local-toolchain-inventory / browser-relay-mcp-status），frontmatter 合规，body 含相应 Why/How 或事实。
- [ ] `MEMORY.md` 索引追加 3 条新条目，无重复、无过时描述残留。
- [ ] 不重复记忆已有内容（新记忆与现有 14 条不重叠，新工具清单只列"全景"，编译细节仍指向 local-build-toolchain）。
- [ ] 一个新会话开局即能在 SessionStart 注入的 CLAUDE.md 里看到 SSH-first / 浏览器验证 / 禁跑 mvn 三条核心行为约束。

## Constraints

- 不改任何代码、不改部署、不重启服务。
- 记忆写入遵循既有 frontmatter 规范（name/description/metadata.type）。
- CLAUDE.md 是行为指令，不是知识仓库；细节交给 auto-memory。
- 不臆造工具状态，所有"已装/在跑"结论必须实测（本会话已实测 SSH/browser-relay/mcpServers）。

## Out of Scope

- 修复 browser-relay MCP 原生工具加载（需重启会话，另议）。
- 重新审阅项目代码质量（用户原话侧重"会话发生的重复性错误"，即行为/记忆层，非代码审阅）。
- Trellis spec/guides 体系调整。
