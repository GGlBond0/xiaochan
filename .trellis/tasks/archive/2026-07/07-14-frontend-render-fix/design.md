# Design — 前端渲染bug修复

## LOC-3 修复方案
`LocationView.vue` `loadAddressList`（line 47-68）：
- 现状：line 55-59 `for (addr of addressList) { if (expandedSpt.has(id)) loadPushTargets(addr.id) }` 仅加载已展开地址。
- 改为：为所有地址加载 spt 计数。考虑地址数一般很少，直接遍历全部调 `loadPushTargets`（并发或顺序均可）。
  ```js
  for (const addr of addressList.value) {
    loadPushTargets(addr.id)   // 去掉 expandedSpt.has 条件
  }
  ```
- `loadPushTargets`（line 303-311）本身是 async、内部赋值 pushTargetMap，不 await 也安全（计数会异步刷新）。
- 副作用：每个地址多一个 `/api/location/{id}/push-target` 请求。地址数少（实测1个），可接受。若担心多地址放大请求，用 Promise.all 并发。
- 展开时仍调 loadPushTargets（已存在逻辑）不影响——刷新一次幂等。

## CARD-4 修复方案
`GrabCardView.vue` line 132-143 统计区模板：
- 现状：line 135 顶部用 `cardCount.ticketCount` 显示饭票；line 138 `v-for d in cardCount.details.filter(x => x.cardId !== 1)` 遍历其余券。
- 问题1：写死 `cardId !== 1` 过滤饭票，153 账号饭票 cardId=5 漏过滤→重复。
- 问题2：同名券（延时券 cardId 228+57）各渲染一项→重复。
- 改为按 name 聚合：计算属性 `aggregatedDetails`，按 name 分组求和 count，并排除饭票（饭票已用 ticketCount 单独显示）。
  ```js
  const aggregatedDetails = computed(() => {
    if (!cardCount.value?.details) return []
    const map = new Map<string, number>()
    for (const d of cardCount.value.details) {
      if (d.name === '饭票') continue   // 饭票用顶部 ticketCount，不在此重复
      map.set(d.name, (map.get(d.name) || 0) + d.count)
    }
    return Array.from(map, ([name, count]) => ({ name, count }))
  })
  ```
  模板 line 138 改为 `v-for="d in aggregatedDetails"`，显示 `{{ d.name }}：{{ d.count }}`，key 用 name。
- 用 name 判断饭票（非 cardId），与后端 ticket-cardid-not-fixed 修复理念一致，对 cardId 1/5/任意都生效。
- 顶部 ticketCount 显示保留不变。

## 兼容性 / 回滚
- 纯前端逻辑改动，数据结构不变，接口不变。
- 回滚：git revert 即可。线上需重新构建部署 dist（见 frontend-deploy-dist-absolute-path 记忆：必须绝对路径打包）。

## 不修复项说明
- CARD-3"loading 卡住"经实测为接口慢（代理超时，同 HOME-1 根因），非前端逻辑缺陷；countLoading 在 finally 已正确置 false。不在本任务修，归入代理治理后续。
