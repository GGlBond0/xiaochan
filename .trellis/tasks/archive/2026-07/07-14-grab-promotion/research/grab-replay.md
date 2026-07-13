# A0 抓包重放验证结论

执行脚本：`research/grab-replay.py`（python3，本机无 java/mvn，用 urllib + hashlib 验证）
日期：2026-07-14

## 测试矩阵

| 用例 | Nami | Garen(时间戳) | X-Ashe | 结果 |
|---|---|---|---|---|
| 基线 | 抓包原值 `6762225593567970` | 原值 1783913626327（过期） | 原值 | **403 Forbidden** |
| 随机+重算 | 随机 `382b0df58dd07475` | 当前时间 | 重算 | **200, code=6「该活动名额已抢完」** |

## 结论

1. **随机 X-Nami 可行**：服务端不校验 Nami 内容，仅参与 X-Ashe 签名。→ 沿用项目 `XiaochanHttp.getNami()` 随机生成，无需持久化原 Nami、无需逆向算法。design 的 Nami 回退方案可简化为单一随机生成。

2. **签名/登录态通过**：`X-Ashe = MD5(MD5((server+"."+method).toLowerCase()) + X-Garen + X-Nami)`，配合当前时间 `X-Garen` + 登录态 header（X-Sivir/X-Teemo/X-Vayne/X-Session-Id），验签通过走到业务层。算法与现有 `XiaochanHttp.getAshe()` 完全一致，可直接复用。

3. **登录态 JWT 仍有效**：payload `{"UserId":5263106,"exp":1786147113}`，距今约 25 天（2026-08-08 失效）。

4. **业务返回码**：
   - code=0：成功，返回 `promotion_order_id`、`timeout`（抓包第一条即此）。
   - code=4：活动未开始（抓包 2、3 条）。
   - code=6：该活动名额已抢完（本次实测，正常业务返回，非签名问题）。

5. **乱码说明**：响应体为 UTF-8，但原抓包/控制台按 Latin-1 显示会花屏（如"该活动名额已抢完"显示为乱码）。后端解析响应必须按 UTF-8 解码。

## 对 design 的修订

- §1.2 / §5 / 风险表「随机 X-Nami 被拒」：**风险消除**，确认随机 Nami 可行，移除回退路径。
- §5 `GrabAuth.nami` 字段可省略（随机生成即可），但保留 `xc_nami` 列无害（未来如需固定可启用），暂保留为可选。
- §4.2/4.3 重试需考虑 code=6（名额已抢完）——此类不应重试（重试也无意义），仅 code=4（未开始）才重试。→ design §4.3 第 5 步增加：code=6 直接失败不重试。

## 后续

A0 通过。可进入 A1（DDL）。抢单功能对真实抢券已可工作（JWT 有效），后续用真实未抢完的 promotion_id 即可端到端验证 code=0。

## 端到端验证（2026-07-14 生产部署后）

- 部署到 121.91.175.192，DDL 执行成功（user 加 5 字段 + grab_config/grab_history 建表）。
- 启动一次失败：`XiaochanHttp` 非 Spring Bean（项目用 `new`），改 GrabServiceImpl 用 `new XiaochanHttp()` 后正常。
- 重新绑定登录态：用户id=5263106（取自 X-Vayne / JWT.UserId），JWT 剩余 25 天。
- 创建抢单配置 promotion=118226923（披萨，库存9），silk_id=222559356。
- **手动抢单 → code=0，promotionOrderId=713921753，抢单成功**。
- 配置自动 DISABLE、grab_history 落库（success/respCode=0/attempt=1/MANUAL）、推送发出。

## 关键发现：x-Teemo ≠ userid

抓包 `favorites1.json`：
- `x-Teemo: ["222559356"]`  = **silk_id**（与 body `silk_id:222559356` 一致）
- `X-Vayne: ["5263106"]`     = **用户id**（与 JWT `UserId:5263106` 一致）

初版实现误把 x-Teemo 当 userid（header 两个都填 userid、登录态解析把 teemo 存为 xc_user_id）。**已修正**：
- `XiaochanHttp.getGrabHeaders`：`x-Teemo=silkId`、`X-Vayne=userId`。
- `GrabServiceImpl.saveLoginState`：xc_user_id 只取 X-Vayne / JWT.UserId，不再取 x-Teemo。

→ design §1.2 header 表已标注此映射。抢单请求必须传 silk_id（来自 grab_config.silk_id），登录态仅存 userid。

