# Implement — 执行清单

## 文件
- `C:/D/AI/Projects/xiaocan/xiaocan-front-main/src/views/LocationView.vue`
- `C:/D/AI/Projects/xiaocan/xiaocan-front-main/src/views/GrabCardView.vue`

## 1. LOC-3：地址 spt 计数初始加载
- [ ] LocationView.vue `loadAddressList` line 55-59：去掉 `if (expandedSpt.value.has(...))` 条件，为所有地址调 `loadPushTargets(addr.id)`（可改 Promise.all 并发）。
- [ ] 确认 `loadPushTargets` 幂等，展开时再次调用不报错。

## 2. CARD-4：卡券统计区按 name 聚合
- [ ] GrabCardView.vue script：新增 computed `aggregatedDetails`，按 name 分组求和、排除饭票（name==='饭票'）。
- [ ] 确认 import 了 `computed`（检查现有 import）。
- [ ] 模板 line 138：`v-for="d in cardCount.details.filter(...)"` 改为 `v-for="d in aggregatedDetails"`，key 用 `d.name`，显示 `{{ d.name }}：{{ d.count }}`。
- [ ] 移除写死的 `cardId !== 1` 过滤逻辑。

## 3. 构建验证
- [ ] 前端仓库 `npm run build` 无编译错误。
- [ ] （可选）本地 dev server 自查，或留待用户部署后线上验证。

## 4. 回归自检
- [ ] LOC-3：登录态计数仍正确（loadLoginStates 全量逻辑不动）。
- [ ] CARD-4：券明细列表（card-grid，line 144+）不受影响——只改统计区，列表仍按每张券渲染。
- [ ] 饭票顶部 ticketCount 展示保留。

## 验证命令
```bash
cd C:/D/AI/Projects/xiaocan/xiaocan-front-main && npm run build
```

## 回滚点
- 仅前端改动，git revert 恢复；线上需重建 dist 部署。
