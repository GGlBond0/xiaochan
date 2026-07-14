# Implement — 跨会话长效记忆缺口补齐

## 执行顺序

### 1. 新建仓库根 CLAUDE.md
- 路径 `C:\D\AI\Projects\xiaocan\xiaocan-main\CLAUDE.md`
- 5 条行为指令，每条 `[[记忆名]]` 引向细节，≤80 行。
- 内容点：SSH-first、browser-relay 验证（含 HTTP API 兜底）、禁生产跑 mvn、工具链绝对路径、部署勿臆造。

### 2. 新增 3 条 auto-memory
顺序写，frontmatter 合规（name/description/metadata.type）：
1. `ssh-first-behavior.md`（feedback，含 Why/How to apply）
2. `local-toolchain-inventory.md`（reference，工具全景表）
3. `browser-relay-mcp-status.md`（reference，工具可用性+HTTP API 兜底）

### 3. 更新 MEMORY.md 索引
- 追加 3 条新行，按现有格式 `- [Title](file.md) — hook`。
- 审阅现有 14 行有无过时（本会话已知无过时，仅追加）。

### 4. 自检（review gate）
- `cat CLAUDE.md | wc -l` ≤ 80。
- 5 条 `[[记忆名]]` 引用的记忆文件确实存在。
- MEMORY.md 3 条新行与文件对应。
- 新记忆与现有 14 条无内容重叠。

## 验证命令

```bash
wc -l < CLAUDE.md                              # 期望 ≤80
ls memory/*.md | wc -l                         # 期望 17（14+3，不含 MEMORY.md）
grep -c '\[\[' CLAUDE.md                       # 期望 ≥5
```

## Rollback

- CLAUDE.md 是新增文件，回滚=删除即可。
- 新记忆是新增文件，回滚=删 3 文件 + 撤 MEMORY.md 追加行。
- 不影响任何运行态，无副作用回滚。
