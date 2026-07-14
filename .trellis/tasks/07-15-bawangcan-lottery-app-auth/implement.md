# 执行计划：霸王餐抽奖刷任务改 App 版

> 依赖：`prd.md` / `design.md` / `research/capture-app-lottery.md`。
> 前端在独立仓库 xiaocan-front-main（见记忆 frontend-repo-path），后端在当前仓库。

## 执行顺序与检查点

### 阶段 A — 后端数据层
- [x] A1. `ddl.sql`：把 `lottery_auth` DDL 替换为 design.md §2.1 的新 App 表（`DROP IF EXISTS` 重建，加 `sivir`/`city_code`，删 `nami`）。
- [x] A2. `LotteryAuthEntity`：删 `nami`，加 `sivir`(String)、`cityCode`(Integer)，类注释改 App 登录态。
- [x] A3. `LotteryAuthMapper`：不动（接口不变）。

### 阶段 B — 后端 HTTP/POJO
- [x] B1. `LotteryHttp.BASE_URL`：`gw.xiaocantech.com` → `gwh.xiaocantech.com`。
- [x] B2. `LotteryHttp.getMiniHeaders` → `getAndroidHeaders`（design §3.2）：加 `X-Platform=Android`/`X-Sivir`/`x-Annie=XC`/`X-Version=3.18.3.3`/`x-City`/Android UA，删 `appid`/`version`/`xweb_xhr`/`X-Model`/`Referer`/小程序 UA/`Accept`。`postAuth` 调用名同步改。
- [x] B3. `LotteryHttp.baseBody`：删 `body.put("app_id", ...)`；`APP_ID` 常量若不再用则删。
- [x] B4. `LotteryAuth` POJO：加 `sivir`、`cityCode`；`isComplete()` 加 `sivir` 必填；删 `nami` 字段及 `postAuth` 里"读 auth.nami 否则随机"分支，统一随机生成 `getNami(silkId)`。

### 阶段 C — 后端 Service/Controller
- [x] C1. `LotteryServiceImpl.saveAuth`：解析新增 `X-Sivir`(必填)、`x-City`(可选)；`sivir` 缺失报错；去掉 `nami` 提取；完整性校验改 `silkId+sessionId+sivir`。Entity 写入 `sivir`/`cityCode`，不写 `nami`。
- [x] C2. `LotteryServiceImpl.runTask`：`LotteryAuth.builder()` 加 `.sivir(...).cityCode(...)`，去掉 `.nami(...)`。
- [x] C3. `LotteryServiceImpl`：`TYPE_TO_DESC` 注释补"tp_ad/douyin_mall 纯接口刷不到"。
- [x] C4. `LotteryAuthVO`：加 `cityCode`（可选），**不加 sivir**（JWT 不回前端）。
- [x] C5. `LotteryAuthDTO`：注释更新为 App 登录态（字段不变）。
- [x] C6. `LotteryController`：注释更新（路由/参数不变）。

### 阶段 D — 编译验证（本地，JDK17+Maven）
- [x] D1. `mvn -DskipTests compile` 本地编译通过（BUILD SUCCESS）。
- [x] D2. **不在生产服务器跑 mvn**（见记忆 prod-build-avoid-server）。

### 阶段 E — 前端（独立仓库 xiaocan-front-main，我去改）
- [x] E1. 设置页抽奖登录态录入区文案改"小蚕 App 登录态"，提示从 App 抓包（带 X-Sivir）。
- [x] E2. 确认调 `/api/lottery/auth`、`/api/lottery/auth/list`、`/api/lottery/run` 接口不变（DTO 字段不变），列表展示加 cityCode 列。
- [ ] E3. 前端构建打包用绝对路径部署（见记忆 frontend-deploy-dist-absolute-path，部署阶段做）。

### 阶段 F — 实测验证（已部署生产 + 真实 Android 登录态 222559356）
- [x] F1. 前端粘贴 App 抓包头（含 X-Sivir/x-City），保存成功，列表出现该登录态；DB 记录 id=1, sivir/city_code=440111 入库正确。
- [x] F2. 直连 `gwh.xiaocantech.com` 验证 `LotteryInfo` 返回 `code:0`（day_num=8，今日所有可刷任务已完成）；签名/header/body 全对。
- [x] F3. `AddLotteryTimes(type=10)` 对已完成任务返回 `code:40040`（"浏览福利页只能一次"），HTTP 200 + 业务码非0 —— runTask 据此 `item.ok=false,msg=...`，不抛异常不中断，符合预期。
- [x] F4. 调 `/api/lottery/run?authId=1`（直连模式）：`success:true`，`beforeCount=0/afterCount=0/tasks:[]`（今日全完成→无未完成任务→tasks 空，前后 count 不变），流程跑通无异常。
- [~] F5. 分享(type=2)/JWT 过期场景未单独触发（今日该账号任务全完成，无未完成项可刷；JWT 有效期约 2026-08-04，待自然过期或换日后再验证 401）。401 结构化失败逻辑沿用 mini 版未改，已 review 确认。
- 注：代理池质量差导致首次经代理 runTask 超时卡住；临时关 PROXY_ENABLED 直连验证后已恢复 `PROXY_ENABLED=true`。代理超时为环境问题，非本次代码缺陷。

### 阶段 G — spec 更新
- [x] G1. `.trellis/spec/backend/xiaocan-rpc-contract.md`：更新抽奖刷任务契约为 App 版（端点 gwh、Android header、body 无 app_id、type 2/8/9/10/11、tp_ad/douyin_mall 刷不到），保留 mini 版作历史说明或标注废弃。
- [~] G2. 记忆 `proxy-config-settings` 等无需改；可补一条"App 抽奖登录态独立表"项目记忆（见 finish 阶段）。

## 验证命令

```bash
# 后端本地编译（项目根目录）
mvn -q -DskipTests compile
# 确认关键改动点
git diff -- src/main/java/io/github/xiaocan/http/LotteryHttp.java
git diff -- src/main/java/io/github/xiaocan/http/LotteryAuth.java
git diff -- src/main/java/io/github/xiaocan/service/impl/LotteryServiceImpl.java
git diff -- src/main/java/io/github/xiaocan/model/entity/LotteryAuthEntity.java
git diff -- ddl.sql
```

## Review Gates

- 阶段 B/C 完成、本地编译通过（D1）后，先自检 header/body 是否与抓包样本逐字段一致，再进实测。
- 实测 F2 通过前不视为完成。
- spec G1 在 finish 阶段（3.3）统一更新。

## 回滚点

- D2 编译失败 → 修代码，不回滚。
- F2 实测失败（接口不通）→ 先核对端点/header（可能与抓包样本有出入），必要时 git revert 阶段 B/C 代码回退到 mini 版临时可用。
- DDL 破坏性删表 → 生产部署前在测试库跑 DDL 验证；生产部署同步告知"需重新录入 App 登录态"。
