# Design: 生产运行健康审查

## 方法

纯 SSH 只读。主入口一条命令链尽量聚合取数，避免反复登录；大日志在服务器端 grep/awk 归类后只回传统计结果，不把日志原文拉回本地。

## 取数命令骨架（服务器端）

1. **configId 执行频次**：
   ```bash
   grep -oE 'configId: [0-9]+' /opt/xiaocan/logs/info.log | sort | uniq -c | sort -rn
   ```
2. **configId 结局分布**：
   ```bash
   grep -E 'configId: [0-9]+ (找到|没有满足条件|发送消息|清理)' /opt/xiaocan/logs/info.log \
     | grep -oE 'configId: [0-9]+ (找到[0-9]+个|没有满足条件的门店活动|发送消息|清理.*)' \
     | sort | uniq -c
   ```
3. **error.log 归类**：先 `grep -E 'Exception|ERROR|Caused by'` 取类型，再对高频类型取前后栈。
4. **systemd/资源**：
   ```bash
   systemctl show xiaocan -p NRestarts -p ActiveEnterTimestamp -p MemoryCurrent
   journalctl -u xiaocan --since '7 days ago' | grep -iE 'OOM|killed|restart|started|stopped'
   ps -o rss,vsz,etime,cmd -p $(pgrep -f xiaocan.jar)
   df -h /opt
   grep -iE 'HikariPool|Connection' /opt/xiaocan/logs/info.log | tail -20
   ```
5. **前端可达性**：
   ```bash
   curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/
   curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:10234/api/<probe>
   ```
6. **线上 vs 声明**：`mysql -e "SELECT id,type,cron,ext_config,status FROM monitor_config"`（SELECT 只读）。

## 产出结构（audit-prod-health.md）

1. 概览（服务状态/运行时长/资源概要/前端可达）
2. 各 configId 执行与健康表（id / 类型 / cron / 近期执行次数 / 结局分布 / 判定）
3. error.log 异常归类表（类型 / 次数 / 触发 configId / 样例栈 / 严重度）
4. 问题清单（按严重度排序，带证据 + 是否需修 + 建议归属）
5. 「确认正常」模块清单

## 约束

- 只读：不改 monitor_config、不 restart、不动 nginx。
- error.log 取栈时脱敏（去掉用户敏感字段，如有）。
- 证据以 `日志时间 + 行关键字` 或命令输出计数呈现，不贴大段原文。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 大 grep 拖累小机 | 当天日志 <400KB，全 grep 秒级；不跨多天聚合 |
| 误把「无库存/距离不符」当 bug | R2 结局分布里「没有满足条件」需结合上游是否返回数据判断，单列观察项不直接判 P1 |
| MySQL 直连需密码 | 用生产 application.yaml 已配的连接；只 SELECT，不写 |
