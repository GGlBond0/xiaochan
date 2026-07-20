# 执行计划：霸王餐新增看视频/看商城/领累计奖励任务

> 纯后端任务（前端已确认零改动）。本地构建 + 部署生产。

## 执行顺序

### Step 1: LotteryHttp 新增两方法 + 常量
- [ ] 1.1 加常量：`ON_AD_VIEWED_METHOD`、`RECEIVE_EXTRA_LOTTERY_METHOD`、`ON_AD_VIEWED_SIGN_KEY="lcjkbqadfrzsewxy"`、`BUS_TYPE_VIEW_TP_AD=2`、`BUS_TYPE_VIEW_DOUYIN_MALL=4`。
- [ ] 1.2 加 `randomNonce6()`（6 位随机小写字母）。
- [ ] 1.3 加 `onAdViewed(LotteryAuth auth, int busType)`：构造 signStr `silk_id=&timestamp=&nonce=&bus_type=`，`HMac(HmacSHA256, key).digestBase64(signStr)`，body `{silk_id,timestamp(秒),nonce,bus_type,sign}`，调 `postAuth(..., ON_AD_VIEWED_METHOD, "onAdViewed")`。
- [ ] 1.4 加 `receiveExtraLottery(LotteryAuth auth, int step)`：body `{silk_id, step}`，调 `postAuth(..., RECEIVE_EXTRA_LOTTERY_METHOD, "receiveExtraLottery")`。
- [ ] 1.5 import `cn.hutool.crypto.digest.HMac` / `HmacAlgorithm`。

### Step 2: LotteryServiceImpl 扩展 runTask
- [ ] 2.1 删除 `LotteryServiceImpl.java:53` 过时注释（is_view_tp_ad/is_view_douyin_mall 纯接口刷不到），改注为"走 OnAdViewed，见 research/capture-extra-tasks.md"。
- [ ] 2.2 抽 `codeOf(JSONObject)` / `msgOf(JSONObject)` 私有方法，重构现有 5 任务遍历用之（减少重复）。
- [ ] 2.3 加 `addAdViewTask(items, li, auth, flag, busType, desc)`：检查 flag 翻转，调 `onAdViewed`，记 TaskItem。
- [ ] 2.4 加 `addStepPrizeTask(items, lp, auth, step, prefix, desc)`：检查 `has_got_*_step_prize` + `lottery_count>=step_count`，调 `receiveExtraLottery`，记 TaskItem（type=100+step）。
- [ ] 2.5 在 5 任务遍历后插入：
  - `addAdViewTask(is_view_tp_ad, BUS_TYPE_VIEW_TP_AD, "看视频")`
  - `addAdViewTask(is_view_douyin_mall, BUS_TYPE_VIEW_DOUYIN_MALL, "看商城")`
  - 取最新 `getLotteryProgress` → `addStepPrizeTask(step=1, "first", "领第一阶梯奖")` + `addStepPrizeTask(step=2, "second", "领第二阶梯奖")`。
- [ ] 2.6 `friendlyMsg` 加 `code==40043 → "阶梯奖已领取"`。

### Step 3: 本地构建
- [ ] 3.1 `C:\D\tools\apache-maven-3.9.16\bin\mvn.cmd -q -DskipTests package`（绝对路径，会话 PATH 无 mvn）。
- [ ] 3.2 确认 `target/xiaocan.jar` 生成、编译无错。

### Step 4: 部署生产
- [ ] 4.1 用 rsync（非 scp，见 [[scp-large-jar-hangs-server]]）传 `target/xiaocan.jar` 到 `root@121.91.175.192:/opt/xiaocan/xiaocan.jar`。
- [ ] 4.2 `ssh root@121.91.175.192 'systemctl restart xiaocan'`。
- [ ] 4.3 `ssh root@121.91.175.192 'systemctl status xiaocan | head -5; tail -30 /opt/xiaocan/logs/*.log'` 确认启动无 NPE。

### Step 5: 线上验证
- [ ] 5.1 浏览器自动化（browser-relay，见 [[browser-relay-setup]]）或前端设置页，选一个**当日看视频/看商城未完成**的霸王餐登录态，点"一键刷任务"。
- [ ] 5.2 确认明细含 9 项（原 5 + 看视频 + 看商城 + 领第一阶梯奖 + 领第二阶梯奖）。
- [ ] 5.3 看视频/看商城未完成时 `status=OK`；已完成 `SKIPPED`。
- [ ] 5.4 领阶梯奖：未达阈值 `SKIPPED("未达阶梯阈值")`，已领 `SKIPPED("已领取")`，达阈值未领 `OK`。
- [ ] 5.5 现有 5 任务行为不回归（已完成 SKIPPED、当日满 401 不重试）。

### Step 6: spec 更新 + 收尾
- [ ] 6.1 更新 `.trellis/spec/backend/xiaocan-rpc-contract.md`：接口清单加 OnAdViewed/ReceiveExtraLottery、sign 算法段、bus_type 映射、删旧"刷不到"表述。
- [ ] 6.2 更新 [[lottery-app-auth-table]] 记忆：能刷任务从 5 个变 9 个（+看视频/看商城/领两阶梯奖）。
- [ ] 6.3 commit、task 归档。

## 验证命令
```bash
# 本地构建
C:\D\tools\apache-maven-3.9.16\bin\mvn.cmd -q -DskipTests package
# 部署（rsync 避免 scp 卡死）
rsync -avz target/xiaocan.jar root@121.91.175.192:/opt/xiaocan/xiaocan.jar
ssh root@121.91.175.192 'systemctl restart xiaocan && sleep 3 && systemctl status xiaocan --no-pager | head -5'
# browser-relay 验活
curl http://127.0.0.1:18795/api/debug
```

## Review Gates
- Step 2 后：自查 `addStepPrizeTask` 的 `has_got_first_step_prize` / `has_got_second_step_prize` 字段名拼写（GetLotteryProgress 响应字段）。
- Step 3 前：确认 sign 算法 Java 实现与 Python 实测一致（密钥/签名串顺序/base64）。
- Step 4 后：确认线上启动无 NPE 再验证。

## Rollback Points
- Step 3 构建失败：不改生产，回 Step 1/2 修代码。
- Step 5 线上验证失败：`git revert` 该 commit，重新构建部署旧 jar。
