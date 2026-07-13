# 前端3km筛选部署到121.91.175.192

## Goal

将 distance-3km 前端改动（commit `6741a26`，已 push 到 `GGlBond0/xiaocan-front` origin/main）构建并部署到生产服务器 `121.91.175.192`，使首页「3km 内」开关、距离排序、监控配置 within3km checkbox 在线上生效。

## Background / 现状（实测，2026-07-14）

- 前端仓库在本地 `../xiaocan-front-main`（sibling 目录），origin = `git@github.com:GGlBond0/xiaocan-front.git`，是 fork（上游 `lyrric/xiaocan-front`）。
- **该 fork 无任何 GitHub Actions workflows** —— 前端无 CI，靠本地 `npm run build` 产出 `dist/` 后 scp 上传。
- 构建脚本（package.json）：`build` = `run-p type-check "build-only"` → `vite build` → 产物 `dist/`。
- 本地 main 与 origin/main 已同步（0 ahead / 0 behind），commit `6741a26` 已推送。
- 服务器 nginx 两个 server block 均服务 `/var/www/xiaocan/dist`：
  - `xiaocan.conf`：`listen 8088`，server_name `_` —— IP:8088 入口
  - `xiaocan-domain.conf`：`listen 80`，server_name `xiaocan.20030704.xyz`
  - 两者的 `/api/` → `proxy_pass http://127.0.0.1:10234`（即本次已部署的后端）
  - nginx 直接读 dist 目录，**无需 reload**。
- 当前线上 `dist/index.html` 时间戳 Jul 13 17:03 —— 是 distance-3km **之前**的旧前端，尚未含本次改动。
- dist 目录属主 `www-data:www-data`。

## Requirements

### R1 本地构建
- 在 `../xiaocan-front-main` 执行 `npm run build`，产出 `dist/`。
- 构建须通过 type-check（`vue-tsc --build`），无 TS/Vue 编译错误。

### R2 部署
- 备份远端现有 `/var/www/xiaocan/dist` → `/var/www/xiaocan/dist.bak.<YYYYMMDD-HHMMSS>`。
- 上传新 `dist/` 内容到 `/var/www/xiaocan/dist/`，保持属主 `www-data:www-data`、权限可读。
- nginx 无需 reload。

### R3 验证
- 新 `index.html` 时间戳为部署时刻；引用的新 assets 哈希与本地 `dist/` 一致。
- `curl http://127.0.0.1:8088/` 返回 200 且 index.html 含新构建标记。
- `curl http://127.0.0.1:8088/api/...` 经 nginx 代理到后端 10234 正常（后端已部署）。
- 浏览器访问 `http://121.91.175.192:8088/`：首页出现「3km 内」开关与「距离」排序项；监控配置页 MINIMUM_PAY/STORE_KEYWORD 出现「仅 3km 内」checkbox，STORE_ACTIVITY 不出现。

## Constraints

- 不改 nginx 配置，不 reload nginx。
- 不动后端（后端 distance-3km 已部署）。
- 距离 3km 半径与后端一致（3000 米）。

## Acceptance Criteria

- [ ] `npm run build` 成功，无编译错误，`dist/` 产出。
- [ ] 远端 `/var/www/xiaocan/dist` 替换为新构建，属主 www-data:www-data。
- [ ] 旧 dist 已备份为 `dist.bak.<ts>`，可回滚。
- [ ] `curl 127.0.0.1:8088/` 返回 200，index.html 为新构建。
- [ ] `/api/` 代理到后端 10234 正常。
- [ ] 浏览器实测首页「3km 内」开关 + 距离排序、监控页 within3km checkbox 生效（需用户确认）。

## 回滚点

- 远端回滚：`rm -rf /var/www/xiaocan/dist && mv /var/www/xiaocan/dist.bak.<ts> /var/www/xiaocan/dist`，nginx 无需 reload。
- 代码层：前端字段均为可选增量，回滚前端不影响已部署后端。

## Out of Scope

- 后端任何改动（已部署完成）。
- nginx 配置变更或新域名。
- 前端 CI/CD 链路搭建（本次仅手动部署）。
