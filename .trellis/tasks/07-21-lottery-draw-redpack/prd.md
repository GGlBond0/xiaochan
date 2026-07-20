# 霸王餐使用抽奖次数开红包

## Goal

霸王餐刷任务已攒到抽奖次数（`GetLotteryProgress.lottery_progress.lottery_count`）。本次目标：**用这些次数开红包（执行抽奖）**，把攒到的次数全部抽完，返回每次中奖明细。

抓包确认（`har/ProxyPin07-20_23_57_52.har`，69 条 gwh 抽奖流量）：抽奖端点 `SilkwormLotteryMobile.Lottery`，body `{"silk_id":N,"prize_type":1}`，每调一次 `lottery_count` 减 1（9→8→…→0），响应 `status.code=0` + `prize`（滴滴5折打车/花小猪100元券包/高德打车券/单号修改券/小蚕红包等）。上次任务（07-20）以"执行抽奖被防水墙挡"为由未做；本次抓包显示连续 10 次全部 `code=0` 成功，证明该端点可调。

## Background / 现状

- 刷任务攒次数：`LotteryServiceImpl.runTask`（已实现：AddLotteryTimes 5 项 + OnAdViewed 看视频/看商城 + ReceiveExtraLottery 阶梯奖）。
- 抽奖（开红包）：`LotteryHttp` **未实现** `Lottery` 方法。本次新增。
- 端点/header/签名算法与现有 LotteryHttp 完全一致（gwh + Android header + X-Ashe），仅 methodName 新增、body 带 `prize_type`。
- 前端在独立仓库 xiaocan-front-main（不在本仓库），本次只做后端；前端调用契约（接口名/返回结构）在 design.md 文档化，供前端另接。

## 逆向结论（2026-07-21 抓包确认）

1. **开红包/抽奖** = `SilkwormLotteryMobile.Lottery`，servername `SilkwormLottery`。
2. body = `{"silk_id":<silkId>,"prize_type":1}`（抓包 10 次全用 prize_type=1）。
3. 响应：
   ```json
   {"status":{"code":0},"prize":{"name":"滴滴5折打车","icon":"...","first_type":3,"second_type":6,"action_extra":{...},"card_id":18,...},"lucky_times":0,"is_lucky":false,"verify_method":0}
   ```
   - `status.code=0` 成功，返回 `prize`（中奖详情）。
   - `lucky_times`/`is_lucky` 在抓包样本中均为 0/false（非大奖流）。
   - `verify_method` 抓包为 0（无需二次验证）。
4. 次数扣减：每调一次 `lottery_count` 减 1，到 0 后再调应返回业务错误码（未抓到，按"抽到 0 即止"循环）。
5. **无 sign**（与 AddLotteryTimes 同，body 无 timestamp/nonce/sign，纯 silk_id + prize_type）。

## Requirements

### R1 后端 HTTP 层
- `LotteryHttp` 新增 `lottery(auth)`：body `{"silk_id","prize_type":1}`，methodName `SilkwormLotteryMobile.Lottery`，servername `SilkwormLottery`。复用 `postAuth`/`getAndroidHeaders`/`getAshe`/`getNami`。
- 沿用现有 401/403/业务码非0 错误处理。

### R2 后端 Service + Controller
- `LotteryService` 新增 `draw(authId)`：
  1. 鉴权 + 构造 LotteryAuth（复用 runTask 的鉴权逻辑）。
  2. `getLotteryProgress` 取 `lottery_count` = N。
  3. 循环 N 次：调 `lottery(auth)`，收集每次 `prize`（name/icon/first_type/second_type/card_id）。中途某次 code!=0 记失败并停止后续（次数可能已耗尽或被风控）。
  4. 返回 `LotteryDrawResultVO`：authName、beforeCount、prizes 列表、afterCount、error。
- `LotteryController` 新增 `POST /api/lottery/draw?authId=N`，返回 `BaseResult<LotteryDrawResultVO>`。
- 多账号串行约束不变（前端逐个调，后端单次 draw 内串行循环 N 次）。

### R3 VO + 前端契约
- 新增 `LotteryDrawResultVO`：authName、beforeCount、`List<DrawItem> prizes`、afterCount、error。
  - `DrawItem`：name、icon、firstType、secondType、cardId、ok（bool）、msg（失败原因）。
- 前端（独立仓库）调用契约文档化于 design.md，本次不实现前端。

## Constraints

- 永不在生产服务器跑 mvn（[[prod-build-avoid-server]]），本地构建 + rsync/分片 scp 部署（[[scp-large-jar-hangs-server]]）。
- 端点 `gwh.xiaocantech.com`，body 无 app_id，header 带 X-Sivir。
- prize_type 固定=1（抓包仅此值，未验证其它）。
- 抽奖循环上限：以 `getLotteryProgress` 返回的 `lottery_count` 为准，不盲循环；加防御性硬上限（如 50 次）防止上游异常导致死循环。

## Acceptance Criteria

- [ ] AC1 `LotteryHttp.lottery` 调 `SilkwormLotteryMobile.Lottery`，body `{"silk_id","prize_type":1}`，code=0 返回 prize。
- [ ] AC2 `POST /api/lottery/draw?authId=N` 鉴权同 runTask（无权/登录态不完整抛 BusinessException）。
- [ ] AC3 账号 lottery_count=N>0 时，draw 返回 N 条 prize 明细，afterCount=0；中途失败则停止并记 error。
- [ ] AC4 lottery_count=0 时，draw 返回空 prizes、afterCount=0，不报错。
- [ ] AC5 现有 `POST /api/lottery/run` 行为不回归。
- [ ] AC6 本地 mvn 构建通过，部署后无 NPE（参考 [[verify-deploy-claims]]）。
- [ ] AC7 防御性循环硬上限生效（异常时不死循环）。

## Notes

- 复杂度：后端单边（http+service+controller+VO），无前端、无逆向分叉（抓包已确认）。属中等，写 design.md + implement.md。
- 抽奖返回的多为打车券/券包（非现金红包），前端展示按 prize.name 即可。
