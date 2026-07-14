# PRD — 修复前端渲染bug:地址spt计数/卡券同名券重复

## 背景
全量测试报告（.trellis/tasks/archive/2026-07/07-14-full-test-optimize/report.md）发现前端渲染 bug。
经源码+线上实测定位，聚焦两个真实前端逻辑缺陷（CARD-3 的"loading 卡住"经深查实为接口慢，非独立前端bug，已降级）。

## 修复范围

### 1. LOC-3：地址页推送 spt 计数初始显示为 0
- 现象：地址列表页，已保存地址"推送 spt"计数初始显示 0，展开后才加载真实数（实际有1个）。
- 根因：`LocationView.vue:55-59`，`loadAddressList` 只为 `expandedSpt` 集合中的（已展开）地址调 `loadPushTargets`，折叠态地址 spt 计数恒为 0。而登录态用 `loadLoginStates()` 全量加载，故登录态计数初始即正确，spt 不一致。
- 期望：地址列表加载后，所有地址的 spt 计数初始即显示真实值。

### 2. CARD-4：卡券统计区同名券重复显示（含饭票 cardId 写死过滤）
- 现象A：饭票在统计区显示两次——顶部"饭票：N 张"(用 ticketCount) + details 循环又一个"饭票：N"。
  根因：`GrabCardView.vue:138` `cardCount.details.filter(x => x.cardId !== 1)` 写死过滤 cardId==1 的饭票；但饭票 cardId 随账号变（183=1，153=5），153 账号饭票 cardId=5 漏过滤 → 重复渲染。与 ticket-cardid-not-fixed 记忆一致（勿写死 cardId）。
- 现象B：延时券显示两次——153 账号 details 有两条"延时券"（cardId 228 count1 + cardId 57 count2），各渲染一个 count-item。183 仅一条则显示一次。用户观察到"一个账号延时券两组、另一个一组"。
- 期望：统计区每个券名只显示一次（饭票不重复，同名券按 name 合并 count 求和）。

## 约束
- 仅改前端（xiaocan-front-main 仓库 LocationView.vue、GrabCardView.vue），不动后端。
- 不破坏现有功能：登录态计数、展开加载、券列表明细渲染保持正常。
- 饭票顶部 ticketCount 展示保留（line 135），仅去除 details 循环里的饭票重复。
- 改完需本地构建（前端仓库 vite build）验证无编译错误；线上验证由用户部署后进行。

## 验收标准
- [ ] LOC-3：地址页加载后，未展开的地址"推送 spt"计数显示真实值（非0），与 `/api/location/{id}/push-target` 返回条数一致。
- [ ] CARD-4-A：卡券统计区饭票只显示一次（顶部"饭票：N张"），details 循环不再重复渲染饭票（对 cardId 1 和 5 都生效）。
- [ ] CARD-4-B：卡券统计区同名券（如延时券）只显示一项，count 为多条记录之和。
- [ ] 前端 `npm run build` 无编译错误。
- [ ] 不引入回归：登录态计数、券明细列表、地址展开 spt 列表均正常。
