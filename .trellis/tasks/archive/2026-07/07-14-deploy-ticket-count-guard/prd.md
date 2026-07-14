# 部署饭票数量查询与抢单前校验到生产

## Goal

将上一任务「饭票数量查询与抢单前校验」(commit cbfbf4b) 的后端改动部署到生产 121.91.175.192，使 `/api/grab/card/count` 接口与 `doGrab` 抢单前饭票校验在真实环境生效，并端到端验证。

## Background

- 改动已本地 `mvn -DskipTests compile` 通过，但仅编译未打包。本次需 package 出可部署 jar。
- 部署链路（已实测，见 memory `deploy-topology` / `backend-proxy-and-build`）：本机 `mvn -DskipTests package` → 产物 `target/xiaocan-*.jar` → scp 到服务器 `/opt/xiaocan/xiaocan.jar`（备份旧 jar 为 `xiaocan.jar.bak.<ts>`）→ `systemctl restart xiaocan`。
- 约束：**不在生产服务器跑 mvn**（会拖垮同机服务，见 memory `prod-build-avoid-server`）；**不用 GitHub Actions `build-prod.yml`**（其「Create Production Config」步骤会重写 application.yaml 为硬编码，破坏 `${MYSQL_*}` 占位符 + 丢 `allowPublicKeyRetrieval`，见 memory `backend-proxy-and-build`）。
- 服务器 xiaocan 服务经 systemd 托管，env 注入密码，强制绑 127.0.0.1；MySQL/Redis 复用。
- 上一任务无 DDL 变更（复用现有 grab_login_state/grab_config/grab_history，无新表新列），故部署**无需执行任何 SQL**。

## Requirements

### 功能需求

- **F1 本地打包**：本机 `mvn -DskipTests package` 产出 `target/xiaocan-*.jar`（JDK17 + Maven3.9，路径见 memory `local-build-toolchain`）。
- **F2 备份+替换**：scp 上传后，在服务器备份当前 `/opt/xiaocan/xiaocan.jar` 为 `xiaocan.jar.bak.<时间戳>`，再替换为新 jar。
- **F3 重启**：`systemctl restart xiaocan`，确认服务 active、启动日志无异常、HikariPool 连库成功、HTTP 200。
- **F4 接口验证**：
  - `GET /api/grab/card/count?loginStateId=<183有效id>` 返回 `{ticketCount, details[]}`，ticketCount 与 `/api/grab/card/list` 中 `cardId==1` 条数一致（183 账户预期 3）。
  - 登录态不存在/无权时返回 BusinessException（BaseResult.error）。
- **F5 抢单前校验验证**：对一个饭票为 0 的登录态触发抢单，确认不调用上游、落 history(code=-1, msg含「饭票不足」)、推送失败通知。（best-effort：若无 0 饭票账号，至少确认饭票>0 账户正常抢单不受影响。）
- **F6 回滚点**：记录新 jar 部署前后的 jar 路径/备份名/commit，回滚 = 用备份 jar 覆盖 + restart。

### 非功能 / 约束

- **N1**：全程不在生产跑 mvn，不触发 build-prod.yml。
- **N2**：重启窗口内 xiaocan 短暂不可用可接受（单用户系统）。
- **N3**：部署前确认 git 工作区为当前 commit cbfbf4b（已提交）。
- **N4**：若服务启动失败，立即用备份 jar 回滚，不等排查。

## Acceptance Criteria

- [ ] **AC1**：本地 `mvn -DskipTests package` 成功，产出 jar。
- [ ] **AC2**：服务器旧 jar 已备份，新 jar 已替换，`systemctl restart xiaocan` 后服务 active、HTTP 200。
- [ ] **AC3**：`/api/grab/card/count?loginStateId=<183id>` 返回 ticketCount 与 card/list 中 cardId==1 数量一致（=3）。
- [ ] **AC4**：无权/不存在的 loginStateId 返回 BusinessException 而非 500 崩溃。
- [ ] **AC5**：饭票>0 账户手动抢单流程不受影响（正常发请求，行为同改动前）。
- [ ] **AC6**：记录回滚信息（备份 jar 名 + 当前 commit）。

## Out of Scope

- 前端（xiaocan-front）部署/改动（另开任务）。
- 饭票为 0 账户的端到端验证（若无现成 0 饭票账号则跳过 F5 强制项）。
- 任何 DDL（无变更）。

## Notes

- 183 账户 loginStateId 需从 `/api/grab/login-state/list` 取（部署后查）。
- 备份命名约定：`xiaocan.jar.bak.<yyyymmddHHMMss>`。
- 轻量任务 PRD-only 即可，部署步骤直接在执行时按 F1-F6 走。
