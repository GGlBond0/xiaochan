# PRD: XiaoChanServiceImpl NPE 防御 + 配置类事务补齐

## Goal

修复审查 B-4（XiaoChanServiceImpl 三处 NPE）+ B-6（ProxyConfig/MerchantBlacklist updateConfig 无事务）+ B-7（UserServiceImpl.register 无事务）。低风险 null 防御 + 事务补齐（这两类配置 service 是 updateById+invalidate 内存缓存，无 A-8 那个 refresh 读未提交的坑，可直接加事务）。

## 核实后不改的项

- **B-5 PushServiceImpl.sendOne 吞异常**：核实发现 A-4 的「sendMessage 后写历史」只在 pushToLocation 整体抛时才不写，但 sendOne 吞单 spt 失败致 pushToLocation 不抛 → 全 spt 失败仍写历史 → A-4 在该场景未生效。彻底修需 sendMessage 返回「是否全部成功」冒泡到 runSingle，属较大重构，**本次不做**（记为已知局限，A-4 已改善主流场景）。
- **B-9 LotteryHttp.getNami 长 id 越界**：silkId 是 Integer 类型（最多10位），`20-10-4=6≥0` 不越界，类型约束保护，**不改**。

## B-4 XiaoChanServiceImpl NPE（核实后三处）

- `:115` `hasNext` 里 `t.getDistance() > MAX_DISTANCE` —— distance null 拆箱 NPE。改 nullsLast 或 null 过滤。
- `:144` `sortStoreList` orderType==1/null 分支 `Comparator.comparing(StoreInfo::getDistance)` —— distance null NPE（:153 orderType==4 已用 nullsLast，此分支未用）。改 nullsLast。
- `:159` `filter` 里 `storeInfo.getName().contains(...)` —— name null NPE。改 null 安全。
- `:63` orderType `!= 1` 审查误判（前面有 null 短路），不改。

## B-6 配置类事务

- `ProxyConfigServiceImpl.updateConfig`：`updateById` + `ProxyHolder.invalidate()` 加 `@Transactional`。invalidate 是内存操作不读 DB，无 A-8 坑，直接加。
- `MerchantBlacklistServiceImpl.updateConfig`：同上加事务。
- 注意：invalidate 推迟到 afterCommit？invalidate 清缓存不读 DB，事务内调即可（即使事务回滚，缓存清了下次重读也是对的——重读到回滚后的旧值，安全）。故无需 afterCommit，直接加 @Transactional。

## B-7 UserServiceImpl.register 事务

- `register`：`lambdaQuery().oneOpt()` + `save(user)` 加 `@Transactional`，并发同 spt 注册由事务 + DB 唯一索引兜底。

## Requirements

- R1 B-4：XiaoChanServiceImpl `:115`/`:144`/`:159` 三处 null 防御，distance/name null 不 NPE。
- R2 B-6：ProxyConfigServiceImpl、MerchantBlacklistServiceImpl 的 updateConfig 加 `@Transactional(rollbackFor=Exception.class)`。
- R3 B-7：UserServiceImpl.register 加 `@Transactional(rollbackFor=Exception.class)`。
- R4 不改业务逻辑、不改前端、不动 B-5/B-9。

## Acceptance Criteria

- [ ] 后端 `mvn -o compile` BUILD SUCCESS。
- [ ] B-4 三处 null 防御到位，distance/name null 不崩。
- [ ] B-6 两个 updateConfig + B-7 register 加事务。
- [ ] 部署上线，正常路径行为不变。

## Out of Scope

- B-5（sendMessage 返回值重构，本次不做）。
- B-8 LocationServiceImpl.delete 调度副作用（跨 service afterCommit，另开）。
- B-9（类型约束已保护）。

## Open Questions

无。
