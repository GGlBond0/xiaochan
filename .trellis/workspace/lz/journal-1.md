# Journal - lz (Part 1)

> AI development session journal
> Started: 2026-07-14

---



## Session 1: 部署小蚕到 Ubuntu 云服务器 + 动态代理绕过 WAF + 域名反代

**Date**: 2026-07-14
**Task**: 部署小蚕到 Ubuntu 云服务器 + 动态代理绕过 WAF + 域名反代

### Summary

在 Ubuntu 22.04 (121.91.175.192) 完成小蚕前后端分离部署：MySQL/Redis 复用+新建、后端 systemd 托管(env 注入密码, 强制绑 127.0.0.1)、前端 dist 经 Nginx 反代。8088 端口 + 域名 xiaocan.20030704.xyz 两种访问方式。fork workflow 重写(后端去硬编码) + 前端加镜像源。解决服务器公网 IP 被小蚕腾讯云 WAF 封禁(403)：给 XiaochanHttp 加动态代理池 ProxyHolder(取代理-缓存-套HTTP代理-403换代理重试)，配置经 env 注入；修 api.xiequ.cn DNS 污染(/etc/hosts 写死真实 IP)。验证搜索地址/活动列表恢复正常。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `23f4584` | (see git log) |
| `20a9f87` | (see git log) |
| `ac86c12` | (see git log) |
| `93230d3` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: distance-3km 后端生产部署 + Trellis 收尾

**Date**: 2026-07-14
**Task**: distance-3km 后端生产部署 + Trellis 收尾
**Branch**: `main`

### Summary

完成 distance-3km 后端部署：安装 gh CLI → PAT 触发 GitHub Actions build-prod.yml(run 29280098246) → 下载 xiaocan-prod-17 artifact → scp 到 /opt/xiaocan/xiaocan.jar → systemctl restart xiaocan。应用 4.47s 启动、HikariPool 连库成功、HTTP 200、定时任务恢复。修正 implement.md 臆造的部署步骤(后端 fork 实为 xiaocan、备份命名 xiaocan.jar.bak.<ts>、前端 fork 无 CI 待探明)。写入 deploy-topology / verify-deploy-claims 记忆。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `cad62c8` | (see git log) |
| `02f615b` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: distance-3km 前端部署+后端代理修复+MySQL连接修复

**Date**: 2026-07-14
**Task**: distance-3km 前端部署+后端代理修复+MySQL连接修复
**Branch**: `main`

### Summary

前端dist部署上线(npm build→scp /var/www/xiaocan/dist→nginx 8088/80 serve)。诊断首页空根因：distance-3km JAR基于fork main(无ProxyHolder代理)直连上游被403。从生产旧JAR javap反编译还原ProxyHolder+executeWithProxy代理逻辑补回源码。本机装JDK17+Maven本地构建(教训:服务器跑mvn致CPU满载卡死重启→MySQL重启→caching_sha2缓存清空→旧JAR缺allowPublicKeyRetrieval陷入崩溃循环)。JAR加allowPublicKeyRetrieval=true修复连库+补代理重建部署。验证:代理获取成功无403、首页30门店、within3km全<=3000、orderType=4升序、浏览器端到端OK。记4条memory。归档2任务。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `d3dcbd5` | (see git log) |
| `6fc2ec0` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 服务器资源精简:停用openclaw/new-api/rustdesk

**Date**: 2026-07-14
**Task**: 服务器资源精简:停用openclaw/new-api/rustdesk
**Branch**: `main`

### Summary

运维任务(无代码改动):检查121.91.175.192资源占用,1.7G内存可用仅297M。排查后用户确认停用openclaw-gateway(307M,systemd user service stop+disable)、new-api(Docker容器stop+restart=no+杀裸进程)、rustdesk(Docker容器stop+restart=no)、openclaw-sbx容器rm。内存可用297M→652M(释放~350M)。全程验证xiaocan服务active+首页HTTP200不受影响。容器仅stop保留可恢复未删镜像。本会话前段已完成distance-3km端到端上线(代理修复+MySQL修复,已归档记录于session3)。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `d3dcbd5` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: bootstrap-guidelines:填充前后端开发规范

**Date**: 2026-07-14
**Task**: bootstrap-guidelines:填充前后端开发规范
**Branch**: `main`

### Summary

推进 Trellis 自动创建的 bootstrap-guidelines 任务：派两个 Explore 子代理扫描后端(Spring Boot3+MyBatis-Plus)和前端(Vue3+TS)真实代码约定，据实填写 .trellis/spec/backend 5个文件 + frontend 6个文件。遵循'记录现状含技术债而非理想'原则，记录既有不一致(Monitory拼写/monitorConfigDTO小写/LocationDTO误带ORM注解/前端大量any/无ESLint测试等)。更新 index 状态为 Filled、prd 三项 checkbox 勾选。归档任务。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `3f69b94` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete
