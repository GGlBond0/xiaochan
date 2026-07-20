# 霸王餐新增看视频/看商城/领累计奖励任务

## Goal

霸王餐抽奖刷任务当前只自动完成 5 个 type（分享/领美团红包/领饿了么红包/浏览福利页/浏览霸王餐页），共对应 `LotteryInfo.lottery_info` 中 5 个标志位。本次目标把**剩下可自动化完成的 3 项**纳入：

1. **看视频抽奖机会** — 标志位 `is_view_tp_ad`
2. **看商城抽奖机会** — 标志位 `is_view_douyin_mall`
3. **领取累计抽奖奖励** — 阶梯奖，`GetLotteryProgress.lottery_progress` 中 `has_got_first_step_prize` / `has_got_second_step_prize`（阈值 `first_step_count` / `second_step_count`）

## Background / 现状

- 现有实现：`LotteryServiceImpl.runTask`（`src/main/java/io/github/xiaocan/service/impl/LotteryServiceImpl.java`）遍历 `FLAG_TO_TYPE`（5 项）逐个调 `AddLotteryTimes(type)`。
- `LotteryServiceImpl.java:53` 注释判定 `is_view_tp_ad` / `is_view_douyin_mall` "在 App 端不走 AddLotteryTimes、纯接口刷不到"。该判定**已被本次逆向推翻**——见 research/capture-extra-tasks.md。
- 现有抓包数据（`har/ProxyPin*.har`、`mitm_mcp_traffic.db`）均为 `gw` 端点抢单/首页流量，无 `gwh` 抽奖流量。

## 逆向结论（2026-07-20 已完成，详见 research/capture-extra-tasks.md）

本次手机 ProxyPin 抓包 + H5 源码逆向，三个接口全部破解且 sign 算法实测验证：

1. **看视频** = `SilkwormLotteryMobile.OnAdViewed`，`bus_type=2`，纯接口可刷（`is_view_tp_ad` 翻 true，`day_num+1`）。
2. **看商城** = `SilkwormLotteryMobile.OnAdViewed`，`bus_type=4`，纯接口可刷（`is_view_douyin_mall` 翻 true，`day_num+1`）。
3. **领累计奖励** = `SilkwormLotteryMobile.ReceiveExtraLottery`，body `{silk_id, step:1或2}`，触发条件 `lottery_count >= step_count && !has_got_*_step_prize`，错误码 `40043`=已领取。
4. **sign 算法**：`HMAC-SHA256`，密钥 `lcjkbqadfrzsewxy`，签名串 `silk_id={silkId}&timestamp={秒}&nonce={6位随机小写}&bus_type={2或4}`，输出 base64。Python 复刻两样本全 MATCH。

**关键差异**：三个新任务走独立方法（`OnAdViewed`/`ReceiveExtraLottery`），body 带 sign/step，**不能复用现有 `addLotteryTimes`**，需 `LotteryHttp` 新增两个方法。端点 `gwh`、header 沿用 `getAndroidHeaders`（server+method 变）。

## Requirements

### R1 逆向补抓（已完成 ✅）
- 三接口契约 + sign 算法已全部破解并实测验证，见 research/capture-extra-tasks.md。

### R2 后端实现
- `LotteryHttp` 新增两方法：
  - `onAdViewed(auth, busType)`：body `{silk_id, timestamp(秒), nonce(6位随机小写), bus_type, sign}`，sign = HMAC-SHA256(密钥 `lcjkbqadfrzsewxy`) base64。methodName `SilkwormLotteryMobile.OnAdViewed`。
  - `receiveExtraLottery(auth, step)`：body `{silk_id, step}`，methodName `SilkwormLotteryMobile.ReceiveExtraLottery`。
- `LotteryServiceImpl.runTask` 在现有 5 任务遍历后，增加：
  - 看视频：`is_view_tp_ad==false` → `onAdViewed(auth, 2)`，记 TaskItem。
  - 看商城：`is_view_douyin_mall==false` → `onAdViewed(auth, 4)`，记 TaskItem。
  - 领阶梯奖：刷后取 `GetLotteryProgress`，`lottery_count>=first_step_count && !has_got_first_step_prize` → `receiveExtraLottery(auth,1)`；second 同理。各记 TaskItem。
- `LotteryTaskResultVO.TaskItem` 明细覆盖新增项（desc 用"看视频"/"看商城"/"领第一阶梯奖"/"领第二阶梯奖"）。
- 沿用现有 401/403/业务码非0 错误处理与 `friendlyMsg` 友好化（新增 `40043`="阶梯奖已领取"），不破坏现有 5 任务行为。
- 删除 `LotteryServiceImpl.java:53` 过时注释，更新 `.trellis/spec/backend/xiaocan-rpc-contract.md` 接口清单。

### R3 前端展示
- `SettingsView.vue` 完成明细区按 `status` 渲染新增任务项（看视频/看商城/领阶梯奖），与现有 5 项一致。
- 领阶梯奖若成功，明细中体现"已领取阶梯奖 N"。

## Constraints

- 永不在生产服务器跑 mvn（见 auto-memory [[prod-build-avoid-server]]），本地构建 + scp/rsync 部署。
- 抽奖接口端点 `gwh.xiaocantech.com`，body 无 `app_id`，header 带 `X-Sivir`（见 `.trellis/spec/backend/xiaocan-rpc-contract.md`）。
- 多账号批量串行（小机 1.7G，禁止并发，见审计报告 F 条）。
- 执行抽奖 `Lottery` 被腾讯防水墙挡，**本次不调执行抽奖**，只做"加机会 + 领阶梯奖"。

## Acceptance Criteria

- [x] AC1 抓包结论文档记录三接口 methodName + body + 响应样本 + sign 算法实测验证（research/capture-extra-tasks.md）。
- [ ] AC2 账号当日看视频未完成时，调 `POST /api/lottery/run` 后该任务 `status=OK` 且 `is_view_tp_ad` 翻 true。
- [ ] AC3 同 AC2，针对看商城 `is_view_douyin_mall`。
- [ ] AC4 账号 `lottery_count>=first_step_count && !has_got_first_step_prize` 时，`runTask` 自动领取，翻 true（second_step 同理）；已领时 `40043` 记 SKIPPED/友好文案不中断。
- [ ] AC5 现有 5 任务行为不回归（已完成 SKIPPED、未完成 OK/FAIL、当日满 401 不重试不中断）。
- [ ] AC6 前端明细区展示新增 4 项，状态语义与现有 5 项一致。
- [ ] AC7 本地 mvn 构建通过，部署到生产后端无 NPE/事务问题（参考 [[verify-deploy-claims]]）。

## Notes

- 第一步逆向结果是分叉点：若看视频/看商城确实不可纯接口刷（如需视频播放回调票据），则 AC2/AC3 改为"文档确证不可刷并说明"，后端只实现领阶梯奖（AC4）。该分叉在 R1 抓包后确认。
- 任务复杂度：逆向 + 后端 + 前端，需 `design.md` + `implement.md`。但 design 的接口契约部分要等 R1 抓包后才能填实，故采用"先抓包→再补 design/implement→再 start"的顺序。
