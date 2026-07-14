# Design — 霸王餐刷任务执行日志明细

## 边界

改动集中在 `LotteryServiceImpl` + `LotteryTaskResultVO`（后端契约）与 `SettingsView.vue` 霸王餐刷任务结果区（前端展示）。`LotteryHttp`、`LoginStateService`、登录态读取逻辑、代理重试机制均不动。

## 契约变更：TaskItem 增加状态枚举

当前 `TaskItem` 只有 `ok: Boolean` + `msg`，无法表达"已完成跳过"。改为状态枚举驱动：

```java
public enum TaskStatus { SKIPPED, OK, FAIL }   // 已完成跳过 / 本次成功 / 本次失败

@Data
public static class TaskItem {
    private Integer type;
    private String desc;
    private TaskStatus status;   // 新增，替代 ok 的语义
    private Boolean ok;          // 保留兼容（= status == OK），前端旧逻辑不依赖但留着
    private String msg;          // 原因/消息：SKIPPED="已完成"；FAIL=失败原因；OK 可空
}
```

- `ok` 保留并赋值 `status == OK`，保持向后兼容，不破坏任何潜在旧消费方。
- `msg` 语义从"原始返回消息"扩展为"原因"：SKIPPED 时填"已完成"，FAIL 时填友好化原因，OK 时留空。

## LotteryServiceImpl.runTask 逻辑调整

当前遍历：已完成 → `continue` 跳过（不记录）。改为：已完成 → 记录 SKIPPED；未完成 → 调 `addLotteryTimes`，按结果记 OK/FAIL。

```
for (Map.Entry<String, Integer> entry : FLAG_TO_TYPE.entrySet()) {
    String flag = entry.getKey();
    int type = entry.getValue();
    TaskItem item = new TaskItem();
    item.setType(type);
    item.setDesc(TYPE_TO_DESC.getOrDefault(type, "type=" + type));

    Boolean done = li == null ? null : li.getBoolean(flag);
    if (Boolean.TRUE.equals(done)) {
        item.setStatus(TaskStatus.SKIPPED);
        item.setOk(false);
        item.setMsg("已完成");
        items.add(item);
        continue;
    }
    // 未完成（含 li==null 或 flag=false）：尝试
    try {
        JSONObject r = lotteryHttp.addLotteryTimes(auth, type);
        JSONObject status = r == null ? null : r.getJSONObject("status");
        int code = status == null ? -1 : status.getIntValue("code");
        boolean ok = code == 0;
        item.setStatus(ok ? TaskStatus.OK : TaskStatus.FAIL);
        item.setOk(ok);
        if (!ok) {
            item.setMsg(friendlyMsg(status == null ? null : status.getString("msg"), code));
        }
    } catch (BusinessException e) {
        item.setStatus(TaskStatus.FAIL);
        item.setOk(false);
        item.setMsg(friendlyMsg(e.getMessage(), -1));
    } catch (Exception e) {
        item.setStatus(TaskStatus.FAIL);
        item.setOk(false);
        item.setMsg(friendlyMsg(e.getMessage(), -1));
    }
    items.add(item);
}
```

关键点：
- `li == null`（lotteryInfo 无 lottery_info）时不再整体跳过遍历，而是把 5 个任务都当"未完成"尝试调用——这与原行为不同，但更合理（原行为是 li==null 直接啥也不做，用户看不到任何明细）。
- **执行顺序修正（2026-07-15 验证时发现 design 疏漏后调整）**：原顺序是 刷前getLotteryProgress → lotteryInfo → 遍历 → 刷后getLotteryProgress。问题：刷前 getLotteryProgress 排在 lotteryInfo 之前，代理在第一步全挂时直接抛异常到 catch，走不到遍历，items 空，用户看不到任何明细——违背 AC4。修正后顺序：**lotteryInfo → 遍历填 items → 刷前/刷后 getLotteryProgress（独立 try，失败只丢统计数字不丢明细）**。lotteryInfo 失败则记录 error 直接返回（无明细可填）；否则 items 必填，后续快照失败不影响明细。
- 各阶段独立 try：lotteryInfo 失败单独 return；刷前/刷后快照各用独立 try 只 log.warn，不抛到外层。这样代理不稳定时用户至少能看到"哪些任务尝试了、结果如何"。

## 友好化原因映射（后端 friendlyMsg）

`LotteryServiceImpl` 内新增私有方法，统一把原始原因转友好文案。映射规则与前端 `friendlyError` 对齐：

| 输入特征 | 友好文案 |
|---|---|
| 含 "状态码错误:-1" 或 "状态码错误:403" | 代理不可用（403 或超时），请更换代理后重试 |
| 含 "代理不可用" | 代理池为空，请配置代理后重试 |
| code == 401 | 当日加机会次数已满或权限不足 |
| 含 "登录态不完整" | 登录态不完整，请补全 silk_id/X-Session-Id/X-Sivir |
| 其余 | 原文透传 |

放在后端而非前端的理由：失败原因 per-task 出现，若由前端逐条映射需复制映射表到每行；后端映射后前端只做展示，更简单。前端顶层 `error` 的 `friendlyError` 映射保留（已有，覆盖整体中断场景，双保险）。

## 前端展示调整（SettingsView.vue）

"完成明细"区当前：`v-if="runResult.tasks && runResult.tasks.length"` 循环 `desc / ok ? 成功 : 失败 / msg`。

改为按 status 着色与文案：
- 状态标签：SKIPPED→"已完成"（灰 `#9ca3af`）、OK→"成功"（绿 `#22c55e`）、FAIL→"失败"（红 `#ef4444`）。
- 原因 `msg`：SKIPPED/FAIL 时显示（小字灰/红），OK 时省略。
- 兼容后端 `ok` 字段：若后端因故未返回 status（旧客户端缓存），按 `ok==true→成功`、`ok==false 且有 msg→失败`、否则→已完成，做降级。

## 数据流

```
LotteryHttp.addLotteryTimes(auth, type)
  → 正常: {status:{code,msg}} 或 401结构化失败 {status:{code:401,msg}}
  → 代理全失败抛 BusinessException("状态码错误:-1")
LotteryServiceImpl 遍历5个flag → TaskItem(status, msg) 入 items
  → 末端 getLotteryProgress 抛异常 → vo.setError(友好文案) 但 items 已填
VO → /api/lottery/run → 前端 runResult
前端: 完成明细区渲染全部5行 + 顶层error区(若有)
```

## 兼容性 / 回滚

- `TaskItem` 新增 `status` 枚举字段，`ok` 保留兼容，旧前端读到 `ok` 仍能工作（只是看不到"已完成"态，显示为"失败"——可接受降级）。
- 后端先部署，前端后部署；中间态旧前端 + 新后端：`ok` 仍在，明细行可能把 SKIPPED 误显为"失败"（因 ok=false），但 `msg="已完成"` 仍会显示原因，用户能理解。回滚：后端 jar 备份恢复 + `systemctl restart`；前端 dist 备份恢复。
- 无 DB schema 变更，无迁移风险。

## 部署顺序

1. 后端：本地 `mvn clean package -DskipTests` → scp jar → 备份旧 jar → 替换 → `systemctl restart xiaocan` → 验证接口返回新结构。
2. 前端：`npm run build` → 绝对路径打包 → scp → 备份旧 dist → 解压 → chown www-data。
3. 验证全完成 / 有未完成 / 代理失败 三种场景。

## 取舍

- `li==null` 时改为尝试全部 5 任务而非静默：更透明，代价是 lotteryInfo 异常时会多打 5 个必然失败的 addLotteryTimes（但 lotteryInfo 失败本身已在外层 try，items 不会填——因为 li==null 的分支在 try 块内，若 lotteryInfo 抛异常会直接跳到 catch 不进遍历）。实际只在 lotteryInfo 成功但无 lottery_info 对象时触发，罕见，可接受。
- 友好映射放后端：per-task 失败原因统一在后端转好，前端只渲染，避免前后端重复维护映射表。
