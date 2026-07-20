# Implement — 霸王餐开红包

## 执行清单（有序）

- [ ] I1 新增 `LotteryDrawResultVO.java`
  - 字段：authName、beforeCount(Integer)、afterCount(Integer)、prizes(List<DrawItem>)、error(String)
  - DrawItem 内部类：name、icon、firstType(Integer)、secondType(Integer)、cardId(Integer)、ok(Boolean)、msg(String)
  - `@Data`，与 LotteryTaskResultVO 同包同风格。
- [ ] I2 `LotteryHttp.java` 新增 `lottery` 方法
  - 常量 `LOTTERY_METHOD = "SilkwormLotteryMobile.Lottery"`
  - `lottery(auth)`：body = baseBody + `prize_type:1`，postAuth(..., LOTTERY_SERVER, LOTTERY_METHOD, auth, "lottery")
- [ ] I3 `LotteryService.java` 接口加 `LotteryDrawResultVO draw(Integer authId)`
- [ ] I4 `LotteryServiceImpl.java` 实现 `draw`
  - 复用鉴权块（与 runTask 一致）；提取为私有方法 `buildAuth(authId)` 复用（可选，避免重复）。
  - before/afterCount 容错；循环 `min(N,50)`；中途失败 break + 记 error；toDrawItem 私有方法。
  - import `LotteryDrawResultVO`、`LoginStateEntity`（已有）。
- [ ] I5 `LotteryController.java` 新增 `POST /api/lottery/draw`
  - `@PostMapping("/draw") public BaseResult<LotteryDrawResultVO> draw(@RequestParam Integer authId)`
- [ ] I6 本地 mvn 构建
  - `mvn -DskipTests package`（用绝对路径，PATH 不带 mvn，见 [[local-build-toolchain]]）
- [ ] I7 部署 + 验证（生产 SSH，见 [[ssh-first-behavior]]）
  - rsync/分片 scp JAR 到生产（[[scp-large-jar-hangs-server]]）
  - `ssh root@121.91.175.192 systemctl restart xiaocan`（服务名以 [[deploy-topology]] 为准）
  - `curl` 调 `/api/lottery/draw?authId=<真实>` 验活，看返回 prizes 与 afterCount=0
  - 看日志 `tail -f /opt/xiaocan/logs/*.log`（[[runtime-logs-on-server]]）确认无 NPE

## 验证命令

```bash
# 本地构建（绝对路径 mvn）
& "C:\<mvn-path>\bin\mvn.cmd" -DskipTests -f C:\D\AI\Projects\xiaocan\xiaocan-main\pom.xml package
# 部署后线上验证
ssh root@121.91.175.192 'curl -s "http://127.0.0.1:<port>/api/lottery/draw?authId=N" -X POST' 
```
（具体 mvn 路径/端口/服务名从 auto-memory [[local-build-toolchain]]/[[deploy-topology]] 取。）

## Review Gate

- I4 实现后自检：循环硬上限 50、before null→n=0、中途失败 break、friendlyMsg 复用、无 NPE 风险（prize null 时 toDrawItem 容错）。
- I6 构建必须通过，否则不部署。
- I7 必须看到真实账号 afterCount=0 + 非空 prizes（或 lottery_count=0 时空 prizes 不报错）才算 AC3/AC4 通过。

## Rollback Point

- I6 失败 → 不部署，修代码。
- I7 部署后 NPE/异常 → 回滚到上一个 JAR（生产应保留前一版本备份），`systemctl restart` 恢复，查日志定位。
