# Implement: 生产运行健康审查

## 执行清单（有序，全部 SSH 只读）

1. **configId 执行频次 + 最后时间**：
   ```bash
   ssh root@121.91.175.192 "grep -oE 'configId: [0-9]+' /opt/xiaocan/logs/info.log | sort | uniq -c | sort -rn; echo '---LAST---'; for id in \$(grep -oE 'configId: [0-9]+' /opt/xiaocan/logs/info.log | sort -u | sed 's/configId: //'); do echo -n \"id=\$id \"; grep \"configId: \$id \" /opt/xiaocan/logs/info.log | tail -1 | cut -c1-19; done"
   ```
   记录：每个 configId 当天执行次数 + 最后一次出现时间。识别「表里有但日志无」的静默配置。

2. **configId 结局分布**：
   ```bash
   ssh root@121.91.175.192 "grep -E 'configId: [0-9]+ (找到|没有满足条件|发送消息|清理)' /opt/xiaocan/logs/info.log | grep -oE 'configId: [0-9]+ (找到[0-9]+个|没有满足条件的门店活动|发送消息|清理[^ ]* 分钟)' | sort | uniq -c"
   ```
   识别只打「没有满足条件」、从不「找到」的 configId。

3. **error.log 全量归类**：
   ```bash
   ssh root@121.91.175.192 "grep -nE 'Exception|ERROR|Caused by' /opt/xiaocan/logs/error.log | grep -oE '[A-Z][a-zA-Z]+(Exception|Error)' | sort | uniq -c | sort -rn; echo '---高频栈样例---'; grep -B1 -A5 'Exception' /opt/xiaocan/logs/error.log | head -60"
   ```
   归类异常类型 + 取高频栈。标反复出现异常的 configId/任务。

4. **systemd 稳定性 + 资源**：
   ```bash
   ssh root@121.91.175.192 "systemctl show xiaocan -p NRestarts -p ActiveEnterTimestamp -p MemoryCurrent; echo '---JOURNAL---'; journalctl -u xiaocan --since '7 days ago' --no-pager | grep -iE 'OOM|killed|restart|started|stopped' | tail -20; echo '---PROC---'; ps -o rss,vsz,etime,cmd -p \$(pgrep -f xiaocan.jar); echo '---DISK---'; df -h /opt; echo '---HIKARI---'; grep -iE 'HikariPool|Connection' /opt/xiaocan/logs/info.log | tail -10"
   ```

5. **前端可达 + 反代**：
   ```bash
   ssh root@121.91.175.192 "curl -s -o /dev/null -w 'home=%{http_code}\n' http://127.0.0.1:8088/; curl -s -o /dev/null -w 'api=%{http_code}\n' http://127.0.0.1:10234/; echo '---nginx---'; nginx -t 2>&1; curl -sI http://127.0.0.1:8088/ | head -5"
   ```

6. **线上 vs 声明（SELECT 只读）**：取 monitor_config 全表 type/cron/status，与日志节奏对照。MySQL 密码从生产 application.yaml 取（见 auto-memory [[backend-proxy-and-build]]），仅 SELECT。
   ```bash
   ssh root@121.91.175.192 "mysql -uroot -p<pwd> xiaocan -e 'SELECT id,user_id,type,cron_expression,status FROM monitor_config ORDER BY id' 2>/dev/null"
   ```
   ⚠ 密码不在命令里硬编明文 → 先 `grep password /opt/xiaocan/application.yaml`（或部署的配置）取，再传给 mysql，或用 `--defaults-file`。本步若取密码有风险，改为读后端已暴露的健康接口。

7. **写 `audit-prod-health.md`**：按 design 结构汇总，每条问题带证据+严重度+是否需修+建议归属；附「确认正常」清单。

## 验证

- 产出文件存在且各小节有真实数据（非 TBD）。
- 每个发现的 configId 静默/异常都能指向日志证据。
- 无任何修改性命令执行过（执行后 `git status` 仅多本 md，无代码/配置改动）。

## 回滚点

- 纯只读，无副作用，无需回滚。
- 若误执行了修改性命令：立刻 `git diff` 看本地是否动配置；生产侧 `systemctl status`/表 SELECT 复核未变更。

## 风险文件

- 无源码改动。唯一新增物：`.trellis/tasks/07-20-audit-prod-health/audit-prod-health.md`。
