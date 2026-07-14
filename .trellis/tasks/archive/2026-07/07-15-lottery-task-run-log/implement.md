# Implement — 霸王餐刷任务执行日志明细

## 执行清单

### 后端
- [ ] 1. `LotteryTaskResultVO.TaskItem` 增加 `TaskStatus status` 枚举字段（SKIPPED/OK/FAIL）；`ok` 保留并语义化为 `status==OK`；`msg` 语义改为"原因"。
- [ ] 2. `LotteryServiceImpl` 新增私有 `friendlyMsg(String raw, int code)` 方法，按 design 映射表转友好文案。
- [ ] 3. `LotteryServiceImpl.runTask` 遍历改为：已完成 → 记 SKIPPED("已完成")；未完成 → 调 addLotteryTimes，code==0 记 OK，否则记 FAIL + friendlyMsg；异常记 FAIL + friendlyMsg。
- [ ] 4. 确认 `li==null` 时遍历行为：按 design 尝试全部 5 个（或确认外层 lotteryInfo 异常已 catch 不进遍历，不会误打）。
- [ ] 5. 本地 `mvn clean package -DskipTests` 编译通过（本机 JDK17+Maven，见 local-build-toolchain 记忆）。**不在服务器跑 mvn**。

### 后端部署
- [ ] 6. scp `target/xiaocan.jar` 到服务器 `/tmp/`。
- [ ] 7. SSH 备份 `/opt/xiaocan/xiaocan.jar` → `xiaocan.jar.bak.<YYYYMMDD-HHMMSS>`。
- [ ] 8. 替换 jar、`chown xiaocan:xiaocan`、`systemctl restart xiaocan`。
- [ ] 9. 验证 `systemctl status xiaocan` active + 日志 HikariPool 连接正常。
- [ ] 10. 调一次 `/api/lottery/run`（curl 或前端）确认返回 tasks 含 status 字段。

### 前端
- [ ] 11. `SettingsView.vue` 完成明细区改为按 status 渲染：SKIPPED 灰"已完成"/OK 绿"成功"/FAIL 红"失败"，FAIL/SKIPPED 显示 msg。
- [ ] 12. 加降级：无 status 字段时按 ok 兜底（ok=true→成功，ok=false&&msg→失败，否则→已完成）。
- [ ] 13. `npm run build`（含 vue-tsc）通过。

### 前端部署
- [ ] 14. 绝对路径打包：`tar czf /tmp/fe-dist.tar.gz -C /c/D/AI/Projects/xiaocan/xiaocan-front-main dist`；校验包含 SettingsView（`grep -ci` 非零）。
- [ ] 15. scp 到服务器 → 备份旧 dist `dist.bak.<ts>` → `rm -rf dist/* && tar xzf -C dist --strip-components=1` → `chown -R www-data:www-data dist`。

### 验证
- [ ] 16. AC1 全完成场景：5 行"已完成"（灰），非空白。
- [ ] 17. AC2 有未完成成功场景：对应行"成功"（绿），已完成行灰。
- [ ] 18. AC3 失败场景：行显示"失败"（红）+ 友好原因，无裸"状态码错误:-1"。
- [ ] 19. AC4 代理全挂：顶层友好 error + 中断前 tasks 明细并存。
- [ ] 20. AC6 既有功能（抢单/登录态/代理设置）不受影响。

## 验证命令

- 后端编译：`mvn clean package -DskipTests`（本地仓库根）
- 后端服务：`ssh root@121.91.175.192 'systemctl status xiaocan --no-pager; tail -20 /opt/xiaocan/logs/info.log'`
- 接口返回结构：前端点刷任务，或 `curl` 带登录态 cookie 调 `/api/lottery/run?authId=<id>`
- 前端构建：`cd /c/D/AI/Projects/xiaocan/xiaocan-front-main && npm run build`
- 前端包校验：`tar tzf /tmp/fe-dist.tar.gz | grep -ciE "SettingsView"`

## 回滚点

- 后端：`ssh root@121.91.175.192 'cp /opt/xiaocan/xiaocan.jar.bak.<ts> /opt/xiaocan/xiaocan.jar && chown xiaocan:xiaocan /opt/xiaocan/xiaocan.jar && systemctl restart xiaocan'`
- 前端：`ssh root@121.91.175.192 'cd /var/www/xiaocan && rm -rf dist && mv dist.bak.<ts> dist && chown -R www-data:www-data dist'`
