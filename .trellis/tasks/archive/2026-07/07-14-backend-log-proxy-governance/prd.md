# PRD — 后端优化:日志脱敏与代理超时治理

## 背景
全量测试报告 P1 问题：SEC-1 日志明文打印密钥/token、HOME-1 首页8-10秒慢、GRAB-1 抢单上游超时。根因均已定位（见 design）。
本任务同时修两项，因都需后端 jar 重新构建+部署+重启生产，合并一次部署。

## 修复范围

### SEC-1：日志敏感信息泄露
- 现象：`/var/log/xiaocan/xiaocan.log` 明文打印代理 API URL（含 vkey 密钥）、用户 spt、token、登录态 JWT、MyBatis SQL 参数。
- 根因：logback.xml root level=DEBUG → MyBatis 全量打印 SQL+参数；业务 log.info 打印完整 DTO（GrabServiceImpl:255）。
- 期望：日志中密钥、token、spt、JWT 脱敏（打码），生产日志级别调高。

### HOME-1 + GRAB-1：代理上游 SSL 超时
- 现象：首页列表8-10秒、抢单 Connection reset/SocketTimeout 高频。
- 根因：`XiaochanHttp.executeWithProxy` 仅在 403 时换代理重试；SocketTimeout/Connection reset 等网络异常直接抛出，不换代理不重试。坏代理一旦命中，整轮重试用同一坏代理。
- 期望：网络异常时换代理重试；合理控制超时；提升上游请求成功率与速度。

## 约束
- 仅改后端（xiaocan-main），不改前端。
- **绝不在生产服务器跑 mvn**（prod-build-avoid-server）：本地 JDK17+Maven 构建后 scp 部署。
- 改动影响所有上游请求，需本地编译+线上验证。
- 部署需备份旧 jar、可回滚（参考 frontend-deploy-dist-absolute-path 的备份回滚思路）。
- 不改变现有接口契约与业务逻辑（抢单成功/失败判定、重试次数配置语义不变），仅优化异常处理与日志。

## 验收标准
- [ ] SEC-1：生产日志不再明文出现 vkey、token、spt、JWT（打码或不再打印）；MyBatis SQL 参数不打印敏感字段。
- [ ] SEC-1：生产 root 日志级别非 DEBUG（INFO 或按需），应用功能不受影响。
- [ ] HOME-1/GRAB-1：上游网络异常（SocketTimeout/Connection reset）时换代理重试，不再直接抛出。
- [ ] 本地 `mvn clean package` 编译通过，jar 生成。
- [ ] 部署后线上 xiaocan 正常启动；首页/抢单功能可用；日志观察异常处理符合预期。
- [ ] 不引入回归：403 换代理逻辑、抢单成功/失败、监控推送均正常。
