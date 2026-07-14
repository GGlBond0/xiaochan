# Implement — 后端日志脱敏与代理治理执行清单

## 文件
- `src/main/resources/logback.xml` — 日志级别
- `src/main/resources/application.yml`（若有 mybatis log-impl）— 确认/调整
- `src/main/java/io/github/xiaocan/http/XiaochanHttp.java` — executeWithProxy 异常换代理
- `src/main/java/io/github/xiaocan/util/`（新增 MaskUtil 或复用现有）— 脱敏工具
- `src/main/java/io/github/xiaocan/service/impl/GrabServiceImpl.java` — log.info DTO 脱敏
- `src/main/java/io/github/xiaocan/service/impl/PushServiceImpl.java` — spt 脱敏
- `src/main/java/io/github/xiaocan/service/SptService.java` — spt 脱敏

## 1. SEC-1 日志
- [ ] logback.xml: root level DEBUG → INFO。
- [ ] 确认 application.yml 无 mybatis-plus StdOutImpl log-impl；若有改默认。
- [ ] 新增/复用 mask 工具：spt/token/JWT 保留首6后4中间打码。
- [ ] PushServiceImpl:99、SptService:45/71 的 spt 日志改用 mask。
- [ ] GrabServiceImpl:255 "保存抢单配置请求" 改打印 configId/promotionId 等非敏感字段，不打印完整 dto。
- [ ] ProxyHolder fetchProxy 的代理 api_url 日志不打印明文 vkey（确认 apiUrlOf 不入日志明文）。

## 2. HOME-1/GRAB-1 代理
- [ ] XiaochanHttp.executeWithProxy: try/catch req.execute()，网络异常 catch → log.warn + ProxyHolder.invalidate() + continue 换代理重试。
- [ ] 保留 403 换代理逻辑。
- [ ] 全部重试失败返回 null（已有调用方处理）。
- [ ] 不改 doGrab 外层重试逻辑（保守）。

## 3. 构建
- [ ] 本地 `mvn clean package -DskipTests`（prod-build-avoid-server：本地构建）。
- [ ] 确认 target/xiaocan.jar 生成。

## 4. 部署（授权后）
- [ ] ssh 备份生产旧 jar：`cp /opt/xiaocan/xiaocan.jar /opt/xiaocan/xiaocan.jar.bak.<ts>`。
- [ ] scp 新 jar 到服务器。
- [ ] 替换 + `systemctl restart xiaocan`。
- [ ] `systemctl is-active xiaocan` 确认 active。
- [ ] tail 日志确认启动无异常。

## 5. 线上验证
- [ ] SEC-1：触发一次抢单配置保存/推送，grep 日志确认无明文 vkey/token/spt/JWT；确认无 MyBatis SQL 参数打印。
- [ ] HOME-1：刷新首页，观察加载速度与后端日志（网络异常应见"换代理重试"warn 而非直接 error）。
- [ ] GRAB-1：抢单测试（若有可抢活动），观察网络异常时换代理重试行为。
- [ ] 回归：403 换代理、抢单成功/失败、监控推送正常。

## 验证命令
```bash
mvn clean package -DskipTests
```

## 回滚点
- 旧 jar 备份 `xiaocan.jar.bak.<ts>`，`cp` 回滚 + `systemctl restart xiaocan`，1 分钟内恢复。
