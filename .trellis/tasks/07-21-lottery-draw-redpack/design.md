# Design — 霸王餐开红包（执行抽奖）

## 边界

本任务只动后端 4 个文件，无数据库变更、无前端、无新依赖：

| 文件 | 改动 |
|---|---|
| `src/main/java/io/github/xiaocan/http/LotteryHttp.java` | 新增 `lottery(auth)` 方法 + `LOTTERY_METHOD` 常量 |
| `src/main/java/io/github/xiaocan/service/LotteryService.java` | 接口新增 `draw(authId)` |
| `src/main/java/io/github/xiaocan/service/impl/LotteryServiceImpl.java` | 实现 `draw`，复用鉴权 + getLotteryCount |
| `src/main/java/io/github/xiaocan/controller/LotteryController.java` | 新增 `POST /api/lottery/draw` |
| `src/main/java/io/github/xiaocan/model/vo/LotteryDrawResultVO.java` | 新增 VO |

## 契约

### 上游 RPC（小蚕 gwh）
- 端点：`POST https://gwh.xiaocantech.com/rpc`
- header：`servername=SilkwormLottery`, `methodname=SilkwormLotteryMobile.Lottery`，其余复用 `getAndroidHeaders`（X-Ashe/X-Nami/X-Garen/X-Platform/X-Sivir 等）。
- body：`{"silk_id":<int>,"prize_type":1}`
- 响应：`{"status":{"code":0},"prize":{...},"lucky_times":0,"is_lucky":false,"verify_method":0}`
- 失败：`status.code != 0`（如次数耗尽/风控），沿用 `postAuth` 的 401/403 分支。

### 对外 HTTP（本系统前端）
```
POST /api/lottery/draw?authId=<int>
→ BaseResult<LotteryDrawResultVO>
```
```json
{
  "code": 200,
  "data": {
    "authName": "xxx账号",
    "beforeCount": 9,
    "afterCount": 0,
    "prizes": [
      {"name":"滴滴5折打车","icon":"https://...","firstType":3,"secondType":6,"cardId":0,"ok":true,"msg":null},
      {"name":null,"icon":null,"firstType":null,"secondType":null,"cardId":null,"ok":false,"msg":"抽奖次数已耗尽"}
    ],
    "error": null
  }
}
```

## 数据流

```
draw(authId)
  ├─ 鉴权（复用 runTask 同款：getByCurrentRequest + loginStateService.getEntity + 归属校验 + LotteryAuth 构造 + isComplete）
  ├─ beforeCount = getLotteryCount(getLotteryProgress(auth))   // 失败 beforeCount=null，但继续
  ├─ N = beforeCount (null→0)
  ├─ for i in 0..min(N, HARD_CAP=50):
  │    r = lotteryHttp.lottery(auth)
  │    code = codeOf(r)
  │    if code == 0: prizes.add(DrawItem from r.prize, ok=true)
  │    else: prizes.add(ok=false, msg=friendlyMsg); error=...; break
  ├─ afterCount = getLotteryCount(getLotteryProgress(auth))   // 失败 afterCount=null
  └─ return VO
```

## 关键决策

1. **循环次数以 beforeCount 为准**，不每轮重查 progress（上游 progress 调用本身也耗一次请求，且抓包显示 lottery_count 每次稳减 1）。加 `HARD_CAP=50` 防上游异常（如 progress 返回荒谬大值）导致死循环。
2. **中途失败即停**：某次 lottery code!=0 → 记失败条目 + 设 error + break。原因：次数可能已被并发耗尽、或被风控，继续重试无意义且加重风控。
3. **before/afterCount 容错**：progress 调用失败 → 对应字段 null，不影响抽奖循环（N 取 0）。与 runTask 的"快照失败不丢明细"策略一致。
4. **prize_type 固定 1**：抓包仅此值，参数化无依据。
5. **不调 runTask**：draw 只消费已有次数，不攒机会。攒与开解耦（用户决策）。
6. **friendlyMsg 复用**：把 LotteryServiceImpl 的 `friendlyMsg`/`codeOf`/`msgOf` 已是 private，draw 同类里可直接调（同类）。

## 兼容性 / 回滚

- 纯新增方法 + 新增 VO + 新增端点，不动现有 runTask 路径，AC5 天然满足。
- 回滚：删 `lottery` 方法 / `draw` / 新端点 / 新 VO 即可，无 schema 变更。
- 部署：本地 mvn package → rsync/分片 scp 到生产（[[scp-large-jar-hangs-server]]），systemd restart。不跑 mvn 在生产（[[prod-build-avoid-server]]）。

## 伪代码（Service.draw）

```java
public LotteryDrawResultVO draw(Integer authId) {
    UserEntity user = userService.getByCurrentRequest();
    LoginStateEntity entity = loginStateService.getEntity(authId);
    if (entity == null || !entity.getUserId().equals(user.getId()))
        throw new BusinessException("无权操作该登录态");
    LotteryAuth auth = LotteryAuth.builder()...build();
    if (!auth.isComplete()) throw new BusinessException("登录态不完整：silk_id 或 X-Session-Id 或 X-Sivir 缺失");

    LotteryDrawResultVO vo = new LotteryDrawResultVO();
    vo.setAuthName(entity.getName());
    List<LotteryDrawResultVO.DrawItem> prizes = new ArrayList<>();
    vo.setPrizes(prizes);

    Integer before = null;
    try { before = getLotteryCount(lotteryHttp.getLotteryProgress(auth)); } catch (Exception e) { log.warn(...); }
    vo.setBeforeCount(before);
    int n = before == null ? 0 : before;
    int cap = Math.min(n, 50);

    for (int i = 0; i < cap; i++) {
        try {
            JSONObject r = lotteryHttp.lottery(auth);
            int code = codeOf(r);
            if (code == 0) {
                JSONObject p = r.getJSONObject("prize");
                prizes.add(toDrawItem(p, true, null));
            } else {
                prizes.add(toDrawItem(null, false, friendlyMsg(msgOf(r), code)));
                vo.setError("第" + (i+1) + "次抽奖失败: " + friendlyMsg(msgOf(r), code));
                break;
            }
        } catch (Exception e) {
            prizes.add(toDrawItem(null, false, friendlyMsg(e.getMessage(), -1)));
            vo.setError("第" + (i+1) + "次抽奖异常: " + e.getMessage());
            log.warn("lottery 第{}次异常: {}", i+1, e.getMessage());
            break;
        }
    }

    try { vo.setAfterCount(getLotteryCount(lotteryHttp.getLotteryProgress(auth))); } catch (Exception e) { log.warn(...); }
    return vo;
}
```

## 前端契约（供 xiaocan-front-main 另接）

- 接口：`POST {API_BASE}/api/lottery/draw?authId={id}`
- 与现有 `/api/lottery/run` 同位置同鉴权，前端在"霸王餐"区新增"开红包"按钮，authId 复用登录态下拉选中项。
- 展示：prizes 列表（name + icon 缩略图 + 成功/失败标记），before/afterCount 对比，error 顶层提示。
