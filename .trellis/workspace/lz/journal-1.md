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


## Session 6: 代理IP池配置可视化设置页（跨前后端，已部署生产）

**Date**: 2026-07-14
**Task**: 代理IP池配置可视化设置页（跨前后端，已部署生产）
**Branch**: `main`

### Summary

将后端 ProxyHolder 代理配置从 systemd EnvironmentFile 改为数据库 proxy_config 表（全局单份 id=1），运行时读 DB + 内存快照，保存经 /api/proxy/config 即时生效无需重启，环境变量降级为兜底默认值。后端新增 ProxyConfig Entity/DTO/VO/Mapper/Service/Controller，ProxyHolder 方法签名不变、HTTP 拉取移出锁外、解析异常整体兜底。前端新增 /settings 路由与 NavBar 顶部入口及 SettingsView 表单页。独立审查修复 4 项隐患（P1-P4）。本机 mvn package + npm run build，部署到生产 121.91.175.192：建表、替 jar、部署 dist、重启服务，端到端 GET/PUT/回显/日志验证通过，两个 fork 已 push main。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `dbb06e4` | (see git log) |
| `a3fa03c` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: 饭票数量查询与抢单前校验

**Date**: 2026-07-14
**Task**: 饭票数量查询与抢单前校验
**Branch**: `main`

### Summary

新增 GET /api/grab/card/count 按 cardId 聚合各类卡券数量（含饭票 cardId==1），复用 getUserCardList 翻页取全(上限200)。GrabServiceImpl doGrab 抢单前校验饭票，为 0 落 history(code=-1)+推送失败不发上游请求规避 WAF 风控，查询失败(null)放行不误杀。抽 countTicketByAuth(auth) 私有方法绕开 getByCurrentRequest，解决定时任务(CRON/ONESHOT)无 HTTP 请求上下文会抛错的隐患。抽出 resolveAuth 复用登录态校验。README 勾掉手动/自动抢单 todo+补更新记录。本地 mvn 编译通过，trellis-check 子代理复核六项 AC 全过无需修复。spec 更新：error-handling 记录定时任务复用方法不能依赖 HTTP 上下文 gotcha，quality-guidelines 加两条 code review 检查项。前端(xiaocan-front)饭票张数展示另开任务。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `cbfbf4b` | (see git log) |
| `1b44c75` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: 部署饭票数量查询与抢单前校验到生产

**Date**: 2026-07-14
**Task**: 部署饭票数量查询与抢单前校验到生产
**Branch**: `main`

### Summary

本地 mvn -DskipTests package 出 target/xiaocan.jar(43MB，commit a9b538e)，scp 上传 121.91.175.192。服务器备份旧 jar 为 xiaocan.jar.bak.20260714092044，mv 替换 + chown xiaocan + systemctl restart xiaocan，09:22:17 Started in 4.15s HTTP 200 active。后端监听 127.0.0.1:10234 经 nginx 反代。无 DDL。端到端验证全部通过：/api/grab/card/count?loginStateId=2(183账户) 返回 {ticketCount:2, details[]}，与 /api/grab/card/list 中 cardId==1 条数=2 交叉一致(探店5/饭票2/超前2/延时2/修改3)；无权/不存在 id 返回 BusinessException「登录态不存在或无权使用」非崩溃；饭票>0 抢单校验放行不阻断。6 项 AC 全过。启动后 ERROR 行为无 token 测试请求的 Tomcat 拒绝页非故障。回滚=备份 jar 覆盖+restart。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: 多spt推送-按地址绑定登录态与推送通道

**Date**: 2026-07-14
**Task**: 多spt推送-按地址绑定登录态与推送通道
**Branch**: `main`

### Summary

推送目标从user级单spt改为按location路由多spt:grab_login_state加location_id、新建location_push_target表、新增PushService收口(按locationId路由+user.spt兜底,定时任务线程安全)、监控/抢单/JWT过期3处调用点改pushToLocation、地址维度spt CRUD+测试推送(无验证码)、前端地址页加登录态/spt绑定段、老登录态页收口只读。已本地构建部署:后端jar scp+DDL+重启(HikariPool OK),前端dist部署,端到端验证spt绑定CRUD/越权/测试推送链路通(WxPusher返回code1001确认HTTP链路通)。spec新增push-routing.md。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `74e1e70` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: 卡券查询bug修复:饭票按name判断+登录态去重+自用免登录

**Date**: 2026-07-14
**Task**: 卡券查询bug修复:饭票按name判断+登录态去重+自用免登录
**Branch**: `main`

### Summary

连续修复多spt推送上线后的几个遗留bug:(1)登录态重复录入-GrabServiceImpl.saveLoginState按(userId,xcUserId)去重,已存在则更新而非新增,清理生产重复行(2)自用免登录-前端App.vue写死固定token自动带入主站不弹登录窗(前端commit 6543439)(3)卡券类型标签错误-超前抢单券误显示超抢券、饭票显示typenull,cardTypeLabel补全映射+未知回退name(前端commit ee2d8c1)(4)饭票cardId随账号不固定(183=1,153=5),原写死cardId==1导致153账号饭票计数0+抢单被误拦,countCards/countTicketByAuth改为按name==饭票判断。全部本地构建部署上线验证通过。记忆新增ticket-cardid-not-fixed。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `c51f9b5` | (see git log) |
| `b1488ee` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 11: xiaocan全量测试与优化(前端渲染+后端日志脱敏+代理治理)

**Date**: 2026-07-14
**Task**: xiaocan全量测试与优化(前端渲染+后端日志脱敏+代理治理)
**Branch**: `main`

### Summary

Browser Relay全量测试8模块出报告;修前端LOC-3地址spt计数初始0、CARD-4卡券同名券重复(含饭票cardId写死过滤);修后端SEC-1日志脱敏(logback INFO+MyBatis Slf4jImpl+MaskUtil)、HOME-1/GRAB-1代理网络异常换代理重试。均已本地构建+部署生产验证+推送GitHub。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `be4010e` | (see git log) |
| `3221560` | (see git log) |
| `ae2ddf1` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete
