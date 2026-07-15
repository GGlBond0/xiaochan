# Directory Structure

> 后端代码组织约定（Spring Boot 3.4 + MyBatis-Plus 3.5，Java 17，包根 `io.github.xiaocan`）。

---

## Overview

经典分层架构，源码根 `src/main/java/io/github/xiaocan/`。包按职责划分，命名遵循 `Xxx角色` 模式。注意：仓库名 `xiaocan`，但 Java 包名 `io.github.xiaocan`、上游拼 `xiaochan`——勿混。

---

## Directory Layout

```
src/main/java/io/github/xiaocan/
├── XiaocanServer.java        # 启动类（@SpringBootApplication）
├── controller/               # REST 控制器（XxxController，@RestController）
├── service/                  # 服务接口（XxxService extends IService<Entity>）
│   └── impl/                 # 实现（XxxServiceImpl extends ServiceImpl<Mapper,Entity>）
├── mapper/                   # MyBatis-Plus mapper（XxxMapper extends BaseMapper<Entity>）
├── model/
│   ├── dto/                  # 请求入参（XxxDTO）
│   ├── entity/               # 数据库实体（XxxEntity）
│   ├── enums/                # 枚举（XxxEnums，注意带 s）
│   └── vo/                   # 返回视图对象（XxxVO）
│   └── (根包下: BaseResult, StoreInfo, *ExtNotifyConfig 等非分层领域对象)
├── config/                   # 配置类 + BusinessException + GlobalResultExceptionHandler
├── constant/                 # 常量、平台枚举
├── tasks/                    # 定时/调度任务（BaseTask 抽象基类 + 子类 + MonitorCronScheduler）
├── utils/                    # 工具类（PageConvertUtil, SpringContextUtil）
└── http/                     # 外部 HTTP 调用（XiaochanHttp, MessageHttp, ProxyHolder）
```

---

## Module Organization

- 新功能按分层放：接口 → `service/`，实现 → `service/impl/`，mapper → `mapper/`，实体 → `model/entity/`。
- 跨实体的领域配置对象（如 `*ExtNotifyConfig`）放 `model/` 根包下，非 dto/vo/entity。
- 外部 HTTP 调用统一放 `http/`，**非 Spring bean**，直接 `new` 或静态方法使用。
- 定时任务放 `tasks/`，继承 `BaseTask`（模板方法：`runSingle` 骨架，子类覆写 `fetchStoreInfos`/`filterStoreInfos`/`afterSuccess`）。
- **BaseTask.runSingle 是所有监控类型(STORE_ACTIVITY/STORE_KEYWORD/MINIMUM_PAY)的统一执行骨架**：`StoreTask`/`MinimumPayService` 都走 `runSingle`，所以"监控命中后的后处理"（如推送、自动抢单）只需在 `runSingle` 一处接入即全覆盖三种类型，无需改每个子类。新增命中后逻辑在 `sendMessage` 之后调即可，外层 try/catch 已隔离。

---

## Naming Conventions

- Controller：`XxxController`，`@RestController` + `@RequestMapping("/api/xxx")`。
- Service 接口：`XxxService` extends `IService<Entity>`；实现：`XxxServiceImpl` extends `ServiceImpl<XxxMapper, XxxEntity>` + `@Service`。
- Mapper：`XxxMapper` + `@Mapper` extends `BaseMapper<XxxEntity>`。**mapper 名可与实体名不一致**（如 `NotifyConfigMapper` → `MonitorConfigEntity`）。
- Entity：`XxxEntity` + `@Data @TableName("下划线表名")`。
- DTO：`XxxDTO`（请求）；VO：`XxxVO`（返回）。
- Enum：`XxxEnums`（带 s），`@Getter @AllArgsConstructor`，构造参数为描述字符串。
- 统一返回：`BaseResult<T>`（`success`/`code`/`msg`/`data`）。

**既有不一致（记录现状，非修正项）**：
- `MonitoryConfigService`（拼写 Monitory，非 Monitor）——既有事实。
- `model/dto/monitorConfigDTO.java` 类名小写开头——既有违规命名。
- `tasks/MinimumPayService` 是 `@Service` 但实为定时任务（继承 BaseTask），命名误导。
- `LocationDTO`/`LocationVO` 带了 `@TableName`/`@TableId` 持久化注解（非实体却带 ORM 注解）——既有误用。

---

## Examples

- 标准分层范例：`LocationController` → `LocationService`/`LocationServiceImpl` → `LocationMapper` → `LocationEntity`，DTO `LocationDTO`，VO `LocationVO`。
- 调度任务范例：`MonitorCronScheduler`（动态 cron）+ `BaseTask`/`StoreTask`/`MinimumPayService`（模板方法任务）。
- 跨子系统桥接范例：`AutoGrabService`（监控命中 → 自动建抢单任务）——在 `BaseTask.runSingle` 命中后接入，按活动时段分两支：定时抢建 `grab_config(auto=0)` + `GrabCronScheduler.refresh` 注册调度；立即抢落 `grab_config(auto=1)` 占位后**异步直接 `doGrab("AUTO")`，不注册 cron**（详见 `GrabCronScheduler` 的 executeAt gotcha 与 `grab_config.auto` 约定）。连接"监控"与"抢单"两个独立子系统而不耦合各自内部逻辑。
- 外部 HTTP 范例：`XiaochanHttp`（上游小蚕网关，经 `ProxyHolder` 代理）。
