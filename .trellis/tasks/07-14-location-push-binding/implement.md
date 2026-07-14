# Implement — 多spt推送-按地址绑定登录态与推送通道

执行顺序:后端数据层 → 后端推送收口与调用点 → 后端管理接口 → 前端对接。每步附验证命令;按 gate 检查通过再进下一步。

## Gate 0:准备
- [ ] 确认当前任务:`python ./.trellis/scripts/task.py current`(应指向 07-14-location-push-binding)。
- [ ] 确认后端工作区干净:`git status`。
- [ ] 前端仓库可访问:`ls C:\D\AI\Projects\xiaocan\xiaocan-front-main\src`。

## 后端

### Step 1:数据模型(DDL + Entity)
- [ ] 1.1 `ddl.sql` 追加:`grab_login_state` 加 `location_id`;新建 `location_push_target` 表(见 design 2.1/2.2)。
- [ ] 1.2 `GrabLoginStateEntity.java` 加 `private Long locationId;`(注意现有是 `Integer userId`,location 表 `id` 是 `Long`,类型对齐)。
- [ ] 1.3 新建 `LocationPushTargetEntity.java`(@TableName、@TableLogic、字段同 DDL)+ `LocationPushTargetMapper.java`。
- [ ] 1.4 `GrabLoginStateDTO.java` 加 `private Long locationId;`。
- **验证**:`mvn -o compile`(本地)。
- **Gate**:编译通过才进 Step 2。

### Step 2:PushService 收口
- [ ] 2.1 新建 `service/PushService.java` 接口(`getPushTargets`/`pushToLocation`/`pushToUser`/`testPush`)。
- [ ] 2.2 新建 `service/impl/PushServiceImpl.java`:
  - `getPushTargets(locationId)`:查 `location_push_target` enabled 去重;空则查 `location.userId`→`user.spt` 兜底。
  - `pushToLocation`:遍历 `getPushTargets` 调 `MessageHttp.sendMessage`,逐个 try/catch 记日志。
  - `pushToUser(userId)`:`user.spt` 推。
  - `testPush(locationId)`:固定文案推。
- **验证**:`mvn -o compile`。
- **Gate**:编译通过 + 单元级手测 `getPushTargets` 逻辑(空地址回退 user.spt)。

### Step 3:推送调用点改路由
- [ ] 3.1 `BaseTask.sendMessage`(行182):改 `pushService.pushToLocation(locationEntity.getId(), body, summary)`;注入 `PushService`。
- [ ] 3.2 `GrabServiceImpl.push`(行629):签名加 `Long locationId`;所有调用点透传 `config.getLocationId()`;改 `pushToLocation(locationId,...))`,无 locationId 回退 `pushToUser`。**先 grep `\.push(` 全部调用点确保不漏**。
- [ ] 3.3 `GrabJwtExpireTask.push`/`checkOne`(行85/65):用 `state.getLocationId()` → `pushToLocation`;空回退 `pushToUser`;注入 `PushService`。
- [ ] 3.4 `SptService`:不动。
- **验证**:`mvn -o compile`。
- **Gate**:编译通过 + grep 确认无残留 `getSpt()` 在 3 个改造点(除 SptService/兜底路径)。

### Step 4:地址维度管理接口
- [ ] 4.1 新建 `controller/LocationPushTargetController.java`:list/post/put/delete/test(见 design 5.2),全部校验 location 属当前用户。
- [ ] 4.2 `service/LocationPushTargetService` + impl:CRUD + 用户归属校验 + 测试推送。
- [ ] 4.3 `GrabLoginStateVO` 加 `locationId`/`locationName`;`listLoginState` 实现回填地址名(联表或内存 map)。
- [ ] 4.4 `saveLoginState`:校验 `locationId` 属当前用户(非空时)。
- [ ] 4.5 新建 `LocationPushTargetVO`/`DTO`。
- **验证**:`mvn -o compile` + 启动后端手测接口(curl/Postman:绑 spt、测试推送)。
- **Gate**:接口可用、测试推送能收到。

## 前端(xiaocan-front-main)

### Step 5:地址页扩段
- [ ] 5.1 `LocationView.vue`:address-card 内加"抢单登录态"段(A)+ "推送 spt"段(B),可折叠。
- [ ] 5.2 段A:按 `locationId` 过滤 `listLoginState` 展示;新增对话框带隐藏 `locationId`;更新/解绑/删除。
- [ ] 5.3 段B:`GET /api/location/{id}/push-target` 列表;新增 spt(无验证码);启停 switch;编辑备注;测试推送按钮。
- [ ] 5.4 `src/api` 新增地址维度接口调用函数。
- **验证**:`cd C:\D\AI\Projects\xiaocan\xiaocan-front-main && npm run build`(type-check 通过)。

### Step 6:老登录态页收口
- [ ] 6.1 `GrabLoginView.vue` 改只读 + 迁移提示;老记录(`locationId` 空)可见。
- [ ] 6.2 `router/index.ts` + `NavBar.vue`:处理导航入口(下线或重定向到地址页)。
- **验证**:`npm run build`。

## Step 7:端到端验证
- [ ] 7.1 地址 A 绑定 spt1 + 登录态L1 → 触发监控命中 → spt1 收到推送。
- [ ] 7.2 抢单 config(locationId=A) 命中 → spt1 收到;无 spt 配置地址回退 user.spt。
- [ ] 7.3 JWT 过期提醒 → 登录态 locationId 对应地址 spt 收到;老记录回退 user.spt。
- [ ] 7.4 测试推送按钮 → 微信收到测试文案。
- [ ] 7.5 老记录 `locationId` 空:不报错,回退兜底。

## Step 8:收尾(Phase 3)
- [ ] 8.1 更新 spec(`trellis-update-spec`):推送机制、地址绑定模型。
- [ ] 8.2 commit:后端 + 前端分仓库提交(前端在 xiaocan-front-main 独立 commit)。
- [ ] 8.3 归档任务。

## 回滚点
- Step 1 DDL 只加列加表,回滚 DROP 即可。
- Step 2/3 推送收口为纯重构,回滚改回 `user.getSpt()`。
- Step 5/6 前端改动独立,可单独 revert。
- 任一 Gate 不过 → 修到通过再前进,不跳步。

## 验证命令汇总
- 后端编译:`mvn -o compile`(在本仓库根目录,本地,禁止生产服务器)
- 前端构建:`cd C:\D\AI\Projects\xiaocan\xiaocan-front-main && npm run build`
- 残留检查:`grep -rn "getSpt()" src/main/java`(仅 SptService 与兜底路径应保留)
- 推送调用点:`grep -rn "MessageHttp.sendMessage" src/main/java`(应集中在 PushService + SptService)
