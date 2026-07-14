# 饭票数量查询与抢单前校验

## Goal

小蚕每次抢单消耗一张「饭票」(`cardId=1`)。现有 `/api/grab/card/list` 能查到卡券列表（含饭票），但未聚合数量，抢单也不做饭票校验——饭票为 0 仍会发请求，白白触发腾讯云 WAF 风控。本任务提供「饭票数量查询接口」并在抢单前校验饭票是否充足，不足直接失败推送，不发无效请求。

## Background

- 卡券查询 `SilkwormCardService.GetUserCardList` 已实现（`XiaochanHttp.getUserCardList`），返回扁平卡片数组，每张票一条记录。
- 2026-07-14 183 账户实测卡券结构：`name=="饭票"` 且 `cardId==1` 的条数 = 饭票张数；`cardType` 为 null。其余卡券：探店券(cardId=25,type=9)、超前抢单券(cardId=58,type=1)、延时券(cardId=57,type=2)、单号修改券(cardId=56,type=6)。
- 饭票文案：`desc="抢活动名额机会（不含大牌专享活动）"`，当日有效（当日 23:59:59 到期）。
- 抢单核心消耗品即饭票；探店券为「探店活动名额」专用，与普通抢单接口 `GrabPromotionQuota` 关系待确认（本期不引入探店券校验）。

## Requirements

### 功能需求

- **F1 饭票数量查询**：`GrabService` 新增按登录态查询饭票数量（及各类卡券数量汇总）的能力，复用现有 `getUserCardList`，按 `cardId==1`(饭票) 计数。需翻页取全（当前单页上限 50，若总数超 50 需分页累加，best-effort 上限可设 200）。
- **F2 数量查询接口**：新增 `GET /api/grab/card/count?loginStateId=`，返回该登录态下各类卡券数量汇总，结构：`{ ticketCount: <饭票数>, details: [{cardId, name, count}, ...] }`。校验登录态归属与 JWT 过期，与 `listCards` 一致。
- **F3 抢单前饭票校验**：`GrabServiceImpl.doGrab` 在发请求前查询饭票数，为 0 时直接落 history(success=false, code=-1, msg="饭票不足...") 并推送失败通知「饭票不足，请先领取」，不发上游请求。手动/定时/一次性触发均生效。
- **F4 README 同步**：勾掉 todo `自动、手动抢购活动`（已实现并部署），补更新记录「饭票数量查询与抢单前校验」。

### 非功能 / 约束

- **N1**：饭票查询经 `ProxyHolder` 代理，复用 `postWithResAuth`，与现有卡券查询同模式。
- **N2**：饭票校验失败不发上游请求，减少风控暴露面。
- **N3**：卡券查询本身也消耗一次上游请求，抢单前校验会增加每次抢单一次额外请求——可接受（一换一，且为本地聚合，单次）。
- **N4**：后端本地构建（`mvn -DskipTests package`），不在生产服务器跑 mvn。
- **N5**：DDL 无变更（复用现有 `grab_login_state`/`grab_config`，无新表/新列）。

## Acceptance Criteria

- [ ] **AC1**：`GET /api/grab/card/count?loginStateId=<有效id>` 返回饭票数量，与 `/api/grab/card/list` 中 `cardId==1` 条数一致。
- [ ] **AC2**：登录态不存在/无权/JWT 过期时，count 接口与 listCards 报同样的 `BusinessException`。
- [ ] **AC3**：当登录态饭票数为 0 时，`doGrab`（手动/定时触发）不调用 `grabPromotionQuota`，落 history(code=-1, msg 含「饭票不足」)，并推送 WxPusher 失败通知。
- [ ] **AC4**：饭票数 > 0 时抢单流程不受影响（正常发请求、重试、落库），与改动前行为一致。
- [ ] **AC5**：本地 `mvn -DskipTests package` 编译通过，现有卡券查询/抢单功能无回归。
- [ ] **AC6**：README todo 勾选 `自动、手动抢购活动`，补更新记录。

## Out of Scope

- 前端（xiaocan-front 独立仓库）：卡券页饭票张数展示另开任务，本期后端先行可独立验证上线。
- 探店券/超前抢单券/延时券等其它卡券的抢单前校验（仅饭票）。
- 饭票「领取」入口（仅查询+校验，不在本期）。
- 多账号饭票汇总（单登录态维度）。

## Notes

- 饭票识别键：`cardId == 1`（name=="饭票" 为辅）。cardType 为 null。
- count 接口建议返回结构：`{ ticketCount: 3, details: [{cardId:1,name:"饭票",count:3}, {cardId:25,name:"探店券",count:5}, ...] }`，前端可按需展示。
- 抢单前校验放 `doGrab` 取登录态之后、位置校验前后均可；建议放登录态校验通过后、活动详情查询前，避免无谓请求。
