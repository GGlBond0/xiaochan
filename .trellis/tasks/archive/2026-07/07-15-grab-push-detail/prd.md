# PRD：抢单推送通知添加店铺名/优惠平台等详细信息

## 背景
`GrabServiceImpl.doGrab` 在抢单成功/失败/拦截/饭票不足/重试耗尽 5 处调用 `push(config, user, summary, body)` 推送结果，body 目前形如：
- `活动{promotionId} 抢到，订单号 {orderId}`
- `活动{promotionId} 失败：{msg}(code={code})`
- `活动{promotionId} 商家"{storeName}"命中黑名单，已拦截`
- `活动{promotionId} 饭票不足，请先领取`
- `活动{promotionId} 重试{maxRetry}次仍为未开始/失败`

仅含活动 id，不含店铺名/平台/优惠明细，用户收到通知后无法直观判断是哪家店、哪个平台、什么优惠。

## 需求
给抢单结果推送的正文补充店铺名、优惠平台、优惠明细等详细信息，让用户一条通知就能看清来源。

## 信息来源（均已存在，无需新增查询）
- **店铺名** `storeName`：`doGrab` 内 `xiaochanHttp.getStorePromotionDetail` 查到的快照（失败为 null）；兜底用 `config.getStoreName()`（建任务时由 `AutoGrabServiceImpl.buildBaseEntity` 写入的活动快照）。
- **优惠明细** `promoDetail`：同上，`buildPromoDetail(promoSnapshot)`；兜底 `config.getPromoDetail()`。
- **平台**：`config.getStorePlatform()`（1=美团/2=饿了么/3=京东），无新增字段。
- **活动 id**：`config.getPromotionId()`（保留）。
- **订单号**：成功分支的 `orderId`（保留）。

## 范围
- 仅改 `src/main/java/io/github/xiaocan/service/impl/GrabServiceImpl.java`。
- 不动 `PushService`/`MessageHttp`/`AutoGrabServiceImpl`（推送通道与建任务逻辑不变）。
- 不动监控命中通知（`BaseTask` 已含店铺信息，不在本次范围）。
- 不动登录态过期提醒推送（`AutoGrabServiceImpl.pushExpireReminder`、`GrabJwtExpireTask`）——与"抢单结果"无关。

## 设计要点
1. 统一封装一个私有方法构造"活动前缀"（含店铺名/平台/优惠明细），5 处 push 复用，避免 5 处各拼一次。
   - 形如：`店铺「{storeName}」({平台}) {优惠明细} 活动{promotionId}`，缺失字段优雅省略（不全拼成 "null"）。
2. 数据来源优先用 `doGrab` 内实时查到的 `storeName`/`promoDetail`（更准），缺失时回退 `config` 快照。
   - 注意 5 处 push 中，"饭票不足"分支发生在查询活动详情之前（`storeName`/`promoDetail` 此时尚未赋值），该分支只能用 `config` 快照。
3. 平台名映射：1→美团、2→饿了么、3→京东，其它/null→不显示平台段。
4. summary（标题）保持现状（"抢单成功"/"抢单失败"/"抢单拦截"），仅正文补充详情。

## 验收标准
- [ ] 抢单成功通知正文含店铺名、平台、优惠明细、订单号、活动id。
- [ ] 抢单失败/拦截/饭票不足/重试耗尽通知正文含店铺名、平台、优惠明细、活动id。
- [ ] 字段缺失时不出现 "null"/"undefined" 字样，对应段落省略。
- [ ] 不改变推送通道、路由、触发时机、防重逻辑；`saveHistory` 落库不变。
- [ ] 编译通过（本地 mvn compile）。

## 非目标
- 不重构推送通道、不新增推送字段/表、不改前端展示。
- 不改监控命中通知、登录态过期提醒。
