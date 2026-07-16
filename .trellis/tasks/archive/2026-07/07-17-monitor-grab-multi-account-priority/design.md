# Design — 监控抢单多账号多平台优先级轮询

## 1. 现状与约束（已核对源码）

- `grab_config` 一条 = 单 (userId, loginStateId, promotionId, storePlatform)，含 cron/executeAt/重试参数/防重键 lastGrabTime。`GrabCronScheduler` 按 cron 周期或 executeAt 一次性定时触发 `doGrab`。
- `MonitorConfigEntity`：`grabLoginStateId`(Integer 单值) / `grabPlatforms`(逗号串集合) / `autoGrab`(Boolean)。
- `AutoGrabServiceImpl.tryCreateFromMonitor(config, store)`：每命中一个 StoreInfo 调一次。内部分定时抢分支（未到点→建 auto=0 任务+注册 cron）/立即抢分支（时段内→建 auto=1 占位+异步 doGrab）。防重键 = (userId,promotionId,当天,auto=1,lastGrabTime IS NULL)。
- `GrabServiceImpl.doGrab(config, triggerType)`：单账号执行。重试循环只对 code==4 重试，其它失败 break+push。**不改 doGrab 对外契约**（R11）。
- 限频 code==70（账号×门店，跨活动跨平台，全平台一致）；code==6 抢完；code==4 未开始。
- 前端 `MonitorConfigView.vue`：`grabLoginStateId` el-select 单选；`grabPlatforms` el-checkbox-group（保存 `join(',')` 顺序天然保留）。

## 2. 关键架构决策：轮换落库方式 = 方案 C（混合）

| 方案 | 说明 | 取舍 |
|---|---|---|
| A 纯内存循环 | AutoGrab 内存里换号/降级，不落库 | 简单，但跨时间"等高优先级到 start"挂不住 |
| B 全落多条 grab_config | 每个(组合,账号)落一条，定时+回调降级 | 能等，但多条间优先级/降级/防重协调复杂 |
| **C 混合（采用）** | 立即抢在 AutoGrab 内存循环换号；仅"未到 start 的最高优先级组合"落一条 grab_config 走 GrabCronScheduler 定时；到点触发后再走内存循环换号 | 立即抢轻量；等待靠现有定时机制；不引入多条间协调 |

**为什么选 C**：用户硬要求"等高优先级组合到 start 期间不碰低优先级"→ 需跨时间等待 → 必须落库+定时（B 的能力）。但把全部(组合,账号)都落库会引入多条优先级/防重协调（B 的复杂）。C 只落"当前最高优先级、且未到 start"的那一条定时任务，到点后回到内存循环处理换号/降级，把"优先级决策"集中在 AutoGrab 的一次调用里，避免多条间状态机。

### 2.1 落库策略细化

- **立即抢（最高优先级组合已到 start）**：不落 grab_config（或仅落一条 auto=1 占位留痕，沿用现有防重），在 AutoGrab 的 `grabExecutor` 内存里按账号优先级循环调 doGrab；成功即停；某账号 code==70→换下一账号；组合内账号耗尽或 code==6→降级下一组合（仍在内存循环内继续，下一组合若未到 start 则转"落定时任务"分支）。
- **等待（最高优先级组合未到 start）**：为该最高优先级组合建一条 auto=0 的 grab_config，`executeAt = 该组合 start`，`loginStateId = 账号优先级第一个`，注册 GrabCronScheduler。**不落其它组合**。到点 GrabCronScheduler 触发 → 调 AutoGrab 的"到点回调"，回到立即抢内存循环（含换号/降级）。
- **降级链跨时间**：若到点抢失败降级到下一组合，而下一组合也未到 start → 再落一条定时任务到其 start。即降级可能产生新的定时任务（逐级等待），符合用户"逐级等"语义。

## 3. 数据模型

### 3.1 monitor_config 改动

| 字段 | 改动 | 说明 |
|---|---|---|
| `grab_login_state_id` | Integer → 保留，语义改为"账号优先级第一个"（兼容旧单值读取） | 不改列类型，避免迁移 |
| **`grab_login_state_ids`** (新增) | VARCHAR(255) NULL | 有序账号 id 串，如 "12,5,8"=12优先。空→回退用 grab_login_state_id 单值 |
| `grab_platforms` | 不改列，语义从"无序集合"升为"有序=优先级" | 旧无序值按串内顺序作优先级（影响轻微，旧配置默认仅美团） |
| **`grab_mode`** (新增) | VARCHAR(16) NULL DEFAULT 'SINGLE' | SINGLE/ALL。空→SINGLE（与现有单账号单次行为一致） |

DDL（写 `ddl.sql` + 服务器执行，见 [[deploy-topology]] [[verify-deploy-claims]]）：
```sql
ALTER TABLE monitor_config ADD COLUMN grab_login_state_ids VARCHAR(255) NULL COMMENT '有序抢单账号id串,顺序=优先级;空回退grab_login_state_id';
ALTER TABLE monitor_config ADD COLUMN grab_mode VARCHAR(16) NULL DEFAULT 'SINGLE' COMMENT '抢单模式 SINGLE抢一个名额/ALL每账号各抢一个';
```

### 3.2 grab_config 是否需要新字段？

C 方案下"等待"只落最高优先级一条，**不需要**在 grab_config 加"组/优先级/降级链"字段。但到点回调需要知道"这是哪次监控命中、降级到哪了"——靠 `auto`+占位 lastResult 留痕不够。

**决策**：grab_config 新增两个轻量字段承载"降级游标"，让到点回调能继续推进降级：

| 字段 | 类型 | 说明 |
|---|---|---|
| `monitor_config_id` (新增) | INT NULL | 溯源到监控配置（拿账号优先级列表/平台优先级/模式） |
| `grab_seq` (新增) | VARCHAR(64) NULL | 降级游标：当前已尝试到的"平台索引:账号索引"，如 "0:2"=第0平台组合已试到第2账号 |

> 评估：也可不加 grab_seq，到点回调重新从 monitor_config 读账号/平台优先级列表、按"本次是该组合"重新算。但"组合内账号已试过哪些"会丢（重试已失败账号）。加 grab_seq 简单可靠。**采用**。

DDL：
```sql
ALTER TABLE grab_config ADD COLUMN monitor_config_id INT NULL COMMENT '监控自动抢来源monitor_config.id;手动/定时为空';
ALTER TABLE grab_config ADD COLUMN grab_seq VARCHAR(64) NULL COMMENT '降级游标 平台索引:账号索引';
```

### 3.3 防重调整

现有防重键 `(userId,promotionId,当天,auto=1,lastGrabTime IS NULL)` 是单账号语义。多账号下：
- 不同账号对同一 promotionId 应可并行抢（不互相挡）。
- 防重应防"同一账号同一活动当天重复建占位"。

**决策**：防重键加入 loginStateId：`(userId,promotionId,loginStateId,当天,auto=1,lastGrabTime IS NULL)`。同账号同活动当天只一个未消费占位；不同账号各自允许。

## 4. 运行流程

### 4.1 tryCreateFromMonitor(config, store) 改造（单次命中单 StoreInfo）

```
1. autoGrab 门禁、平台过滤（store.type ∈ 有序 grabPlatforms）—— 保留现有
2. 解析账号优先级列表 accounts = parseIds(grab_login_state_ids) ?: [grab_login_state_id]
   解析平台优先级（已含 store.type 的位置；本次只处理 store.type 这个组合）
3. 登录态校验：accounts 中过滤掉过期/不存在的 → validAccounts（保持顺序）
   若 validAccounts 空 → pushExpireReminder，return
4. 本次命中的组合 = (store.promotionId, store.type)，其在平台优先级中的位置已知，
   但"降级到下一组合"需要同活动其它平台的 StoreInfo —— 本函数只拿到当前 store 一条。
   【关键问题】见 §5。
5. 时间判断（对该组合 start/end）：
   - 未到 start（now < start）：
       若该组合是"本次命中里最高优先级可等待组合" → 建定时任务 executeAt=start，
         loginStateId=accounts[0]，monitor_config_id=config.id，grab_seq="平台索引:0"
       若不是最高优先级（有更高优先级组合在等）→ 跳过（由更高优先级的调用处理等待）
   - 时段内 → 立即抢内存循环（§4.2）
   - 已过期 → 跳过
```

### 4.2 立即抢内存循环（AutoGrab.grabExecutor 内）

```
for 平台组合按优先级降级遍历（从当前组合起，逐级下一组合）:
    if 该组合未到 start:
        落定时任务到该组合 start，return（逐级等待）
    if 该组合已过期: continue 下一组合
    for account in 该组合的账号优先级列表:
        构造 GrabConfigEntity(loginStateId=account, promotionId, storePlatform=组合type, auto=true占位)
        result = doGrab(entity, "AUTO")
        if success: return（成功即停）
        code = result.code
        if code==70 or 饭票不足 or 登录态过期: continue 下一账号（换号）
        if code==6 or 详情缺失/黑名单/位置无效/未知: break 账号循环 → 降级下一组合
    // 账号循环走完仍未成功 → 降级下一组合
// 全部组合耗尽 → push 失败通知（含尝试概要）
```

### 4.3 到点回调（GrabCronScheduler 触发 auto=0 定时任务）

```
execute(config, "ONESHOT"):
  从 config.monitor_config_id 读 MonitorConfigEntity（拿账号/平台优先级/模式）
  从 config.grab_seq 读降级游标
  → 转入 §4.2 立即抢内存循环（从游标对应的组合/账号继续，含换号/降级）
  成功→DISABLE占位；失败降级→可能再落新定时任务
```

### 4.4 ALL 模式

ALL = 每个勾选账号**各跑一遍 SINGLE**。账号优先级在 ALL 下退化为"参与账号清单"（顺序无额外意义，因为每个账号是它自己，不存在"换号"——ALL 下某账号失败要不要换号？）。

**决策（已与用户确认，选 A）**：ALL 下**不做换号**——每个账号独立、各自在所有命中门店按平台优先级抢，某账号在某门店失败（code==70 被该门店限频）即放弃该门店、继续下一门店，不换别的账号替抢。
- 正确理由（订正前述错误理由）：ALL 目标是"每账号各得、各自尽力"，换号会破坏每账号独立性、让名额分配不均（某账号替另一账号抢，导致一个账号重复得名额、另一账号落空）。
- 实现：ALL = 对 accounts 列表**并行**（grabExecutor 多个 submit）每个账号跑一遍"按门店遍历、门店内组合降级"逻辑，但单账号在某门店内**不换号**（只用自己），失败/降级均在"组合"维度，账号维度固定为自己。
- 即 ALL 下每个账号：遍历命中的各门店；每门店内按平台优先级组合遍历（降级），每组合只用"自己这一个账号"试；code==70/饭票不足/登录态过期 → 该账号放弃该门店（不再降级组合，因同门店该账号被限频则全组合皆限频，降级无意义），转下一门店。

## 5. 关键问题：降级需要"同门店其它组合的 StoreInfo"

`tryCreateFromMonitor` 现在一次只拿一个 `StoreInfo`（一个组合）。但降级要切到同门店的其它 (活动,平台) 组合——这些组合的 StoreInfo 在 `BaseTask.triggerAutoGrab` 的 `availableStores` 里。

**决策**：改 `BaseTask.triggerAutoGrab`——不再"每个 StoreInfo 单独调 tryCreateFromMonitor"，而是**按 storeId 分组**（同门店所有命中组合一组），整组传给 `tryCreateFromMonitor(config, List<StoreInfo> sameStoreCombos)`，让 AutoGrab 在内部按平台优先级对整组排序、降级遍历。这最贴合用户"门店级名额 + 优先级"语义。

- 一次监控执行可能命中多个门店 → 按 storeId 分组后，每组（一门店）一次调用，组间独立、互不降级（不同门店是不同名额）。
- 降级跨 promotionId、跨平台，在"本次命中该门店的所有组合"间按优先级进行：A=美团活动1 抢失败 → 降级 B=饿了么活动2（不同 promotionId，但同门店名额），合理。
- 签名变更：`tryCreateFromMonitor(MonitorConfigEntity config, List<StoreInfo> sameStoreCombos)`。
- ALL 模式下，每个账号对每个门店组各跑一次（账号×门店 独立）。

## 6. 前端改动（xiaocan-front-main/MonitorConfigView.vue）

- `form.grabLoginStateId`(单 number|null) → `form.grabLoginStateIds`(number[])。
- 账号控件 el-select 单选 → **可排序多选**（el-select multiple + 拖拽排序，或 el-transfer，或自定义"选中+上下移"）。Element Plus 无原生可排序多选，用 `el-select multiple` + 选后用拖拽/上下按钮排序。**design 倾向**：el-select multiple 允许多选 + 旁置上下移动按钮调顺序（简单可控）。
- 平台控件 el-checkbox-group → 同样改可排序多选（平台仅3个，可用3个带拖拽的 checkbox 或选中列表+排序）。
- 新增抢单模式 radio（SINGLE/ALL），autoGrab 开时显示。
- 保存：`grabLoginStateIds.join(',')`、`grabPlatforms.join(',')`（顺序即优先级）、`grabMode`。
- 加载反显：split 还原顺序。
- autoGrab=false 时这些字段不提交（置 null）。
- 详情展示：账号显示多账号名（按优先级）、平台按优先级、模式。

## 7. 契约改动汇总

- `monitorConfigDTO` / `NotifyConfigVO` 加 `grabLoginStateIds`(String)、`grabMode`(String)；`grabLoginStateId` 保留兼容。
- `MonitoryConfigServiceImpl.addUpdateConfig` 校验：autoGrab=true 时 `grabLoginStateIds` 非空且每个 id 属于当前用户；autoGrab=false 置空。
- `AutoGrabService.tryCreateFromMonitor` 签名：`(config, List<StoreInfo>)`。
- `BaseTask.triggerAutoGrab` 改按 storeId 分组调用。

## 8. 兼容/回归

- 存量 monitor_config：grab_login_state_ids 空 → 回退 [grab_login_state_id]；grab_mode 空 → SINGLE；grab_platforms 旧无序 → 按串内顺序。AC5。
- autoGrab=false：零影响（AC14）。
- 手动抢/定时抢（非监控）：doGrab 不改契约，grab_config 新字段对它们为 null，无影响（AC15）。
- 回滚：DDL 列保留无副作用（读侧兜底）；前端隐藏多选/模式控件即可回退。

## 9. 待 implement 阶段进一步确认

- D1 ✅ 已定：ALL 模式不换号（§4.4），选 A——失败放弃该门店继续下一门店。
- D2 ✅ 已定：降级按 storeId 分组、跨 promotionId 跨平台（§5）。
- D3 前端可排序多选的具体控件实现（el-select multiple + 上下移按钮）—— implement 阶段定。
- D4 code==70 命中后是否本地短期标记 (账号,门店) 避免同次命中内重复打 70（可选优化，非必需）—— implement 阶段评估。
