# 商家名称关键字黑名单

## Goal

新增"商家名称关键字黑名单"功能：在**监控推送**和**抢单**两个环节，依据商家（门店）名称是否命中关键字黑名单进行过滤——命中则不推送、不抢单。黑名单关键字通过复用代理 IP 池那套"DB + 前端设置页，保存即生效"的机制配置，支持多关键字组合匹配。

## Requirements

### 功能需求
- 在设置页新增"商家黑名单"配置区块，与现有"代理设置"区块同页并存。
- 配置内容：是否启用（开关）+ 关键字规则文本（多行，支持组合）。
- 保存即生效：前端保存后，后续监控/抢单周期立即按新规则过滤，无需重启服务。
- 监控推送环节：`StoreTask`（STORE_ACTIVITY / STORE_KEYWORD）与 `MinimumPayService`（MINIMUM_PAY）在过滤门店列表时，命中黑名单的门店被剔除，不推送、不写推送历史。
- 抢单环节：`GrabServiceImpl.doGrab` 在拉到活动详情、拿到 `storeName` 后、发起抢单请求前判断；命中黑名单则不发起抢单，记一条抢单历史（状态为"黑名单拦截"）并推送一条失败通知，然后 return。
- 黑名单为全局单份配置（id 固定 1），所有登录用户可读可改，无管理员分级——与代理 IP 池配置一致。

### 匹配规则（多关键字组合）
- 一行一条规则；空行忽略；首尾空白去除；比较大小写不敏感（子串包含）。
- 行内用 `&` 分隔多个词，表示 AND：商家名须**同时包含**该行所有词才命中。
- 多行之间为 OR：**任意一行**命中即视为该商家在黑名单中。
- 单词行 = 单子串匹配。
- `enabled=false` 或无任何规则时，不做任何过滤（完全透传，零行为变化）。

### 约束
- 不改 `ProxyHolder` 及代理相关既有逻辑。
- 监控/抢单既有方法签名与调用方零改动；过滤以在现有 stream 链首部追加判断、或在 doGrab 已有局部变量处追加判断的方式插入。
- 后端构建仍走本机 mvn 打包上传 jar，永不在生产服务器跑 mvn（见 [[prod-build-avoid-server]]、[[local-build-toolchain]]）。
- 前端 dist 部署用绝对路径打包（见 [[frontend-deploy-dist-absolute-path]]）。
- 需执行 ddl.sql 新段建表。

## Acceptance Criteria

- [ ] ddl.sql 追加 `merchant_blacklist_config` 建表语句，含 `enabled` / `keywords` 及标准时间、逻辑删除列。
- [ ] 后端新增 Entity / Mapper / Service / ServiceImpl / Controller / DTO / VO 七件套，路由 `GET/PUT /api/blacklist/config`，鉴权复用 `userService.getByCurrentRequest()`。
- [ ] 新增 `MerchantBlacklistHolder` 静态工具类，提供 `isBlacklisted(String storeName)` 与 `invalidate()`，带 5s 内存快照、异常回退"不过滤"，对齐 `ProxyHolder` 形态。
- [ ] `StoreTask.filterStoreInfos` 与 `MinimumPayService.filterStoreInfos` 在 stream 链首部按 `storeInfo.getName()` 命中黑名单剔除。
- [ ] `GrabServiceImpl.doGrab` 拿到 `storeName` 后、重试循环前判断黑名单；命中则记历史 + 推失败通知 + return，不发抢单请求。
- [ ] 前端 `SettingsView.vue` 新增"商家黑名单"配置区块（启用开关 + 关键字多行文本 + 保存/重读），调用 `GET/PUT /api/blacklist/config`，保存成功提示"配置已即时生效"。
- [ ] enabled=false 或关键字为空时，监控/抢单行为与改动前完全一致（回归无回归）。
- [ ] 关键字匹配符合上述 AND/OR 规则，且大小写不敏感。
- [ ] 后端本地 mvn 编译通过；前端 npm run build 通过。

## Notes

- 商家名称字段统一是 `StoreInfo.name`（Java）/ `store.name`（小蚕 API JSON），无 `shopName`/`merchantName`。
- 监控侧商家名不落库；抢单侧 `grab_history.store_name` 已有。
- 复用参考：`ProxyConfig{Entity,Mapper,Service,ServiceImpl,Controller,DTO,VO}` + `ProxyHolder` + ddl.sql `proxy_config` 表。
- 前端复用 `SettingsView.vue` 现有"代理设置"区块的 loadConfig/handleSave 模式，同页新增第二个 `.settings-card`，无需新路由/导航条目。
