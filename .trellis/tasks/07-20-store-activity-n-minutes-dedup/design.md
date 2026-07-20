# Design: STORE_ACTIVITY 去掉命中即停

## 改动点（唯一文件：`StoreTask.java`）

`afterSuccess` 现状（:168-175）：
```java
@Override
protected void afterSuccess(MonitorConfigEntity notifyConfig, List<StoreInfo> availableStores) {
    super.afterSuccess(notifyConfig, availableStores);
    // 仅 STORE_ACTIVITY 通知后停用，STORE_KEYWORD 继续运行
    if (!availableStores.isEmpty() && notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
        monitoryConfigService.toggleStatus(notifyConfig.getId(), MonitorConfigStatusEnums.DISABLE);
    }
}
```

改后：删掉 if 块，仅保留 `super.afterSuccess(...)`（或直接删整个 override，因 super 是空实现，但保留 override 调 super 更稳妥，减少 import 清理）。
```java
@Override
protected void afterSuccess(MonitorConfigEntity notifyConfig, List<StoreInfo> availableStores) {
    super.afterSuccess(notifyConfig, availableStores);
}
```
若 `MonitorConfigStatusEnums` import 仅此处用则一并删 import（grep 确认）。

## 为什么这样就够（机制对照）

| 阶段 | 删除前 | 删除后 |
|---|---|---|
| 当天命中推送 | 写历史 + toggleStatus(DISABLE) | 仅写历史，status 保持 ENABLE |
| 当天下次 cron | 监控已 DISABLE，MonitorCronScheduler 不调度 → 不跑 | checkRepeat 查「今天」有记录 → 跳过 runSingle（当天不重复推） |
| 第二天 0 点后 | 监控仍 DISABLE → **永远不跑** | checkRepeat 查「今天」无记录 → 跑 → 命中新 promotionId → 推送 |

checkRepeat（`execute:90` + `:98-106`）的 `createTime >= 今天0点` 是跨天自动重置的关键，无需额外清理。

## 与 MINIMUM_PAY 机制差异（为何不照搬 N 分钟窗）

- MINIMUM_PAY：监控一批店/一批活动，活动轮换快，N 分钟窗去重 + cleanupExpired 跨天复活。
- STORE_ACTIVITY：盯单店、当天一次性（一家店一天一个名额），当天去重用 checkRepeat（按天）比 N 分钟窗更贴合、实现更简、无副作用。
- 不引入 cleanupExpired 到 STORE_ACTIVITY：其历史靠 checkRepeat 按天判断，N 分钟清理反而可能误清当天记录（这正是 `cleanupExpired` 开头 `type != STORE_KEYWORD` 提前 return 保护的原因，保持不动）。

## 兼容性 / 回滚

- 存量 DISABLE 的 STORE_ACTIVITY 配置：本次不自动复活，用户手动重新启用一次即可。后续命中不再停。
- 回滚：单文件 `git revert` StoreTask.java 即恢复「命中即停」。
- 无 DB 变更。

## 风险

| 风险 | 缓解 |
|---|---|
| 用户不知存量 DISABLE 需手动复活 | 交付说明告知 |
| 监控持续跑增加上游请求（单店每分钟一次 searchList） | 与现状 cron 频率一致，checkRepeat 在 execute 入口就挡掉当天重复（不进 fetchStoreInfos），无额外请求负担 |
| checkRepeat 万一失效导致当天重复推 | 现有逻辑已验证；且推送有 WxPusher 侧 idempotency 兜底，低风险 |
