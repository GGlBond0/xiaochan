# 抓包结论：看视频/看商城/领阶梯奖上报接口（2026-07-20）

> 抓包：手机 ProxyPin 导出 `har/ProxyPin07-20_23_40_52.har`，账号 silk_id=93975109，user_id(X-Vayne)=185822。
> 当日看视频/看商城任务初始未完成（LotteryInfo #19：`is_view_tp_ad:false, is_view_douyin_mall:false`）。
> H5 页源码逆向：`https://gw.hzaiguojiang.com/static/act/lottery.html` → `web.xinyifm.cn/silk/lottery/lottery/static/js/chunk-8c4cbf5e.6bf895061.js`。

---

## 1. 看视频 / 看商城 = `SilkwormLotteryMobile.OnAdViewed`（端点 gwh，server SilkwormLottery）

**推翻原判定**：`LotteryServiceImpl.java:53` 注释"is_view_tp_ad/is_view_douyin_mall 在 App 端不走 AddLotteryTimes、纯接口刷不到"——**错**。真相是走独立方法 `OnAdViewed`，纯接口可刷。

### 时序铁证
| step | ts(UTC) | method | body | LotteryInfo 前后 |
|---|---|---|---|---|
| 初始 | #19 15:39:21 | LotteryInfo | — | `is_view_tp_ad:false, is_view_douyin_mall:false, day_num:7` |
| **看视频** | #24 15:39:27.398 | **OnAdViewed bus_type=2** | `{"silk_id":93975109,"timestamp":1784561967,"nonce":"otiwnw","bus_type":2,"sign":"q0sDkKXioXZWcyCbyi9WdkMhGmfd6kJXYJpyaTRBhGM="}` | #25: `is_view_tp_ad:true, day_num:8` ✅ |
| **看商城** | #27 15:40:40.844 | **OnAdViewed bus_type=4** | `{"silk_id":93975109,"timestamp":1784562040,"nonce":"rmclrx","bus_type":4,"sign":"VE/uY7ZCRhj5pxVCj2FYVTIJ4MAA58oEexUNTZ3dM/k="}` | #28: `is_view_douyin_mall:true, day_num:9` ✅ |

### bus_type 映射
- `bus_type=2` → 看视频（`is_view_tp_ad`）
- `bus_type=4` → 看商城（`is_view_douyin_mall`）
> bus_type 与 AddLotteryTimes 的 type 是两套独立编号，勿混。

### OnAdViewed body 字段
```json
{
  "silk_id": 93975109,
  "timestamp": 1784562040,   // 秒级时间戳（X-Garen 是毫秒，body.timestamp 是秒！）
  "nonce": "rmclrx",         // 6 位随机小写字母
  "bus_type": 4,             // 2=看视频, 4=看商城
  "sign": "..."              // base64(HMAC-SHA256)
}
```

---

## 2. sign 算法（已逆向 + 实测验证 ✅）

H5 源码 `chunk-8c4cbf5e.js` `handleShowSpecialRedRain` 函数：
```js
let i = "";
for (let u = 0; u < 6; u++) i += String.fromCharCode(Math.floor(26 * Math.random()) + 97);  // 6位随机小写
const r = Math.floor(+new Date / 1e3);  // 秒级时间戳
let s = `silk_id=${+loginUser.silkId||0}&timestamp=${r}&nonce=${i}&bus_type=${e}`;
const n = "lcjkbqadfrzsewxy";            // ← HMAC 密钥（硬编码）
const o = ye.a.HmacSHA256(s, n);          // HMAC-SHA256
const a = ye.a.enc.Base64.stringify(o);   // base64
const l = { silk_id:+loginUser.silkId||0, timestamp:Math.floor(+new Date/1e3), nonce:i, bus_type:e, sign:a };
await Object(u["i"])(l);   // u["i"] = OnAdViewed
```

### 算法规范
- **签名串**：`silk_id={silkId}&timestamp={秒}&nonce={6位随机小写}&bus_type={2或4}`（注意 `&` 分隔、顺序固定、`silk_id` 为数字）
- **算法**：HMAC-SHA256
- **密钥**：`lcjkbqadfrzsewxy`（硬编码，所有账号通用）
- **输出**：base64（44 字符）

### 实测验证（Python 复刻，两样本全 MATCH）
```python
import hmac, hashlib, base64
key = b"lcjkbqadfrzsewxy"
s = f"silk_id=93975109&timestamp=1784561967&nonce=otiwnw&bus_type=2"
# HMAC-SHA256 → base64 = q0sDkKXioXZWcyCbyi9WdkMhGmfd6kJXYJpyaTRBhGM=  ✅
s = f"silk_id=93975109&timestamp=1784562040&nonce=rmclrx&bus_type=4"
# → VE/uY7ZCRhj5pxVCj2FYVTIJ4MAA58oEexUNTZ3dM/k=  ✅
```

### Java 实现（hutool）
```java
import cn.hutool.crypto.digest.HmacAlgorithm;
String signStr = "silk_id=" + silkId + "&timestamp=" + tsSec + "&nonce=" + nonce + "&bus_type=" + busType;
String sign = new HMac(HmacAlgorithm.HmacSHA256, "lcjkbqadfrzsewxy".getBytes()).digestBase64(signStr);
```

---

## 3. 领累计抽奖奖励 = `SilkwormLotteryMobile.ReceiveExtraLottery`（端点 gwh，server SilkwormLottery）

### 接口契约
- **methodName**：`SilkwormLotteryMobile.ReceiveExtraLottery`
- **serverName**：`SilkwormLottery`
- **body**：`{silk_id, step}`（H5 调用处 `Object(u["k"])({step:l})` 只传 step，但与 OnAdViewed 一致保守带 silk_id）
- **触发条件**（H5 `receive(l)` 函数）：
  - `step=1`：`lottery_count >= first_step_count && !has_got_first_step_prize`
  - `step=2`：`lottery_count >= second_step_count && !has_got_second_step_prize`
- **响应**：`{"status":{"code":0}, "prize": {...}}`（成功返 prize 信息）
- **错误码**：`40043` = 该阶梯奖已领取（H5 `receive` catch 中显式判断 `40043`）

### H5 源码铁证
```js
// u["k"] = ReceiveExtraLottery
const c = async l => {
  if (!a.value) {
    a.value = true;
    try {
      if (1 === l) {
        if (t.value >= e.value && !i.value) {  // lottery_count >= first_step_count && !has_got_first_step_prize
          const t = await Object(u["k"])({ step: l });
          n.value = t.prize; o.value = true; i.value = true;
        }
      } else if (2 === l && t.value >= s.value && !r.value) {  // lottery_count >= second_step_count && !has_got_second_step_prize
        const t = await Object(u["k"])({ step: l });
        n.value = t.prize; o.value = true; r.value = true;
      }
    } catch (d) {
      if (40043 == d.status.code) { /* 已领取提示 */ }
    }
  }
};
```

### GetLotteryProgress 响应字段（已知，本次抓包 #22 确认）
```json
{"lottery_progress":{"first_step_count":3,"second_step_count":9,"lottery_count":0,
"has_got_first_step_prize":false,"has_got_second_step_prize":false}}
```
- 阈值：first_step_count=3, second_step_count=9（累计机会数达此值可领阶梯奖）

---

## 4. 请求头（OnAdViewed / ReceiveExtraLottery 通用，沿用现有 LotteryHttp.getAndroidHeaders）

抓包 OnAdViewed header（与 AddLotteryTimes 基本一致）：
- `servername: SilkwormLottery`，`methodname: SilkwormLotteryMobile.OnAdViewed`（或 ReceiveExtraLottery）
- `X-Ashe`（网关签名，沿用 getAshe，server+method 变）
- `X-Nami`（silk_id 嵌入，沿用 getNami）
- `X-Garen`（毫秒），`X-Platform: Android`，`X-Version: 3.18.0.6`（本次版本，比上次 3.18.3.3 旧；UA `xcapp;3.18.0.6;Android`）
- `x-Annie: XC`，`X-Session-Id`，`x-Teemo=silk_id`，`X-Vayne`，`x-Sivir`(JWT)，`x-City`
- **无 appid**（App 版）
- `Origin/Referer: https://gw.hzaiguojiang.com/`（H5 托管域，非 gwh）

> 后端复用 `LotteryHttp.getAndroidHeaders` + `postAuth` 即可，只需新增 methodName 和 body 构造。X-Version 用 `3.18.3.3`（现有常量）或 `3.18.0.6`（本次抓包）均可——服务端不强校验版本（抓包两个版本都通）。

---

## 5. 三个新任务的完整契约汇总

| 任务 | methodName | body | 触发条件 | 成功标志 |
|---|---|---|---|---|
| 看视频 | `OnAdViewed` | `{silk_id, timestamp(秒), nonce(6位随机小写), bus_type:2, sign}` | `is_view_tp_ad==false` | LotteryInfo `is_view_tp_ad:true`, `day_num+1` |
| 看商城 | `OnAdViewed` | `{silk_id, timestamp, nonce, bus_type:4, sign}` | `is_view_douyin_mall==false` | `is_view_douyin_mall:true`, `day_num+1` |
| 领 first 阶梯奖 | `ReceiveExtraLottery` | `{silk_id, step:1}` | `lottery_count>=first_step_count && !has_got_first_step_prize` | `has_got_first_step_prize:true` |
| 领 second 阶梯奖 | `ReceiveExtraLottery` | `{silk_id, step:2}` | `lottery_count>=second_step_count && !has_got_second_step_prize` | `has_got_second_step_prize:true` |

### 三个新任务 vs 现有 5 任务的差异
- 现有 5 任务走 `AddLotteryTimes(type)`，body 仅 `{silk_id, type}`，无 sign。
- 新任务走 `OnAdViewed`/`ReceiveExtraLottery`，body 带 sign 或 step，**不能复用 `addLotteryTimes`**，需 `LotteryHttp` 新增两个方法。

---

## 6. 其它 gwh 接口（顺带记录，非本次目标）
- `SilkwormLotteryMobile.GetRewardUsers` — 抽奖中奖名单展示
- `SilkwormLotteryMobile.IsShowStepLottery` — 阶梯抽奖开关（#20 返回 `show:true`）
- `ActivityTaskMobileService.GetDailyTask`（server ActivityTask，body 带 user_id/city_code）

---

## 7. 下一步
逆向全部完成，三接口契约齐全 + sign 算法实测验证。可直接进入 `design.md` / `implement.md` 编写，然后 `task.py start` 进实现。
