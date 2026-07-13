# 首页3km筛选与距离排序及监控3km条件

## Goal

在小蚕（xiaochan）前后端分离项目中，为首页门店列表新增「3km 内距离筛选」与「按距离排序」能力，并在监控配置中新增「3km 内」条件项，使监控任务可按距离半径过滤命中门店。

## Background / 现状

- 前端 `xiaocan-front-main`（Vue3 + TS + Element Plus，无 Pinia），后端 `xiaochan-main`（Spring Boot + MyBatis-Plus，无 XML mapper）。
- 首页接口 `POST /api/xiaochan/query`，请求体 `QueryListVO`：`name`、`orderType(1=默认/2=返现金额/3=返现比例)`、`cityCode`、`latitude`、`longitude`、`onlyAvailable`、`pageNum`、`pageSize`。
- 门店 `StoreInfo.distance`（米，Integer）由上游小蚕平台返回并已在前端展示（`formatDistance`），**后端未本地计算距离**。
- 后端 `XiaoChanServiceImpl.sortStoreList`：orderType=1 默认按 distance 升序；2/3 按返现排序。`filter` 仅按 name、onlyAvailable 过滤，**无距离上限筛选**。`MAX_DISTANCE=3500` 仅用于翻页停止判断。
- 监控配置 `monitor_config.ext_config`（JSON），三种扩展配置：`StoreExtNotifyConfig`（指定门店）、`MinimumPayExtNotifyConfig`（最小实付，无距离过滤）、`StoreKeywordExtNotifyConfig`（关键字 + `limitDistance` 布尔，命中时硬编码 `distance<=3500`）。
- 距离阈值 3500 为硬编码常量，无可配置半径字段。
- 「默认地址」是前端 localStorage 记忆（`selectedAddressId`），后端 `location` 表无 `is_default` 字段、无单条默认地址接口。地址对象含 `latitude/longitude/cityCode`。

## Requirements

### R1 首页距离筛选（3km 内）
- 首页提供「3km 内」筛选开关（默认关闭）。开启后仅展示距离 ≤ 3000 米的门店。
- 筛选基于请求中已有的 `latitude/longitude`（来源：用户默认地址；无默认地址时基于浏览器定位）。
- 3km 为固定半径，本次不提供可调节半径档位。

### R2 首页距离排序
- 首页排序新增「距离」选项，按距离升序排列门店。
- 与现有 orderType（默认/返现金额/返现比例）并列，作为新的排序枚举值。
- 距离排序需依赖门店 `distance` 字段；无 distance 的门店排到末尾。

### R3 监控配置「3km 内」条件
- 在监控配置的条件项中复用现有筛选机制，新增「距离 ≤ 3km」子条件。
- 适用范围：`MINIMUM_PAY`（最小实付）与 `STORE_KEYWORD`（关键字）两类配置；`STORE_ACTIVITY`（指定门店）本身已锁定单店，不新增此条件。
- 半径固定 3km（3000 米），与首页保持一致。
- 该条件作为独立可开关项，与配置现有条件以 AND 关系组合。

## Constraints

- 不改变现有接口契约的字段语义，仅做向后兼容的增量扩展（新增可选字段/枚举值）。
- 不引入本地经纬度距离计算；沿用上游返回的 `StoreInfo.distance`。
- 不引入 DB schema 对 `location` 默认地址的改动（默认地址仍由前端 localStorage 记忆）。
- 后端构建链路：fork 仓库 push → GitHub Actions 构建 artifact → scp 上传 → systemd 重启（详见 memory 部署拓扑）。

## Acceptance Criteria

- [ ] 首页开启「3km 内」开关后，返回门店列表全部满足 `distance <= 3000`（米）。
- [ ] 首页「距离排序」选项生效时，列表按 `distance` 升序，无 distance 的门店排末尾。
- [ ] 「3km 内」开关与「距离排序」可独立或组合使用，互不冲突。
- [ ] 监控配置 `MINIMUM_PAY` / `STORE_KEYWORD` 可勾选「3km 内」条件；勾选后监控任务命中门店全部满足 `distance <= 3000`。
- [ ] 未勾选「3km 内」时，监控行为与改动前完全一致（向后兼容）。
- [ ] `STORE_ACTIVITY` 配置不出现「3km 内」条件项。
- [ ] 前端构建产物可正常部署到 121.91.175.192:8088；后端 JAR 构建并重启后功能正常。

## Out of Scope

- 可调节半径档位（1/5/10km 等）——本次固定 3km。
- 后端 `location` 表默认地址字段/接口改造。
- 本地 haversine 距离计算（沿用上游 distance）。
- 监控任务执行历史页面的距离展示改造。
