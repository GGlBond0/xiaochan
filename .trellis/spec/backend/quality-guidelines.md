# Quality Guidelines

> 后端代码质量约定。

---

## Overview

Spring Boot 3.4.1 / Java 17 / Jakarta EE 命名空间。无 lint 配置、无 checkstyle、无测试套件（`pom.xml` 无测试依赖，`-DskipTests` 构建）。质量靠模式一致性 + code review。

---

## Required Patterns

- **依赖注入**：用 `jakarta.annotation.Resource`（JSR-250），**不用 `@Autowired`**。见所有 controller/service/task。
- **Controller**：`@RestController` + `@RequestMapping("/api/xxx")`；方法 `@PostMapping`/`@GetMapping`/`@DeleteMapping`/`@PutMapping`；`@RequestBody @Valid XxxDTO`（`@Valid` 非 `@Validated` 用于方法参数）。
- **统一返回**：Controller 返回 `BaseResult<T>`，用 `BaseResult.ok(data)`；异常交全局处理器。
- **日志**：类级 `@Slf4j`，占位符 `{}`。
- **Lombok**：`@Data`（实体/DTO/VO/BaseResult）、`@Slf4j`（类级）、`@Builder/@AllArgsConstructor/@NoArgsConstructor`（需构建器时）、`@Getter @AllArgsConstructor`（枚举）。
- **JSON**：fastjson2（`com.alibaba.fastjson2`）——`JSONObject.toJSONString` 序列化、`JSONObject.parseObject(str, Class)` 反序列化、`TypeReference<List<...>>` 解析数组。
- **HTTP**：Hutool（`cn.hutool`，`hutool-all`）——`HttpUtil.createPost/Get`、`HttpRequest`/`HttpResponse`、`MD5.create().digestHex`。
- **DTO/VO 转换**：`org.springframework.beans.BeanUtils.copyProperties(source, target)`（Spring 的，**非 Apache**）。
- **事务**：service 写操作 `@Transactional(rollbackFor = Exception.class)`。
- **CORS**：`WebConfig` 全局 `allowedOriginPatterns("*")` + `allowCredentials(true)`。

---

## Forbidden Patterns

- **不要用 `@Autowired`**——用 `@Resource`。
- **不要给 DTO/VO 加 `@TableName`/`@TableId`**——既有 `LocationDTO`/`LocationVO` 误用，勿重复。
- **不要在 Controller 写 try/catch**——交全局异常处理器。
- **不要在生产服务器上跑 `mvn`**——会拖垮同机服务（事故教训，见 memory `prod-build-avoid-server`）。本机构建后 scp 部署。
- **不要用 GitHub Actions `build-prod.yml` 构建**——其「Create Production Config」步骤会把 application.yaml 重写成硬编码，破坏占位符 + 丢 `allowPublicKeyRetrieval`（见 memory `backend-proxy-and-build`）。
- 不要字符串拼接日志——用 `{}` 占位符。

---

## Testing Requirements

- **当前无测试**（`-DskipTests` 构建，无测试依赖）。本任务不强制新增测试，但新功能改上游交互逻辑时应至少手动 `curl` 验证接口。
- 部署后验证模式：`curl /api/...` 打接口 + 查 `logs/info.log`/`error.log`（见 distance-3km 部署的 V1-V8）。

---

## Code Review Checklist

- 改动是否经代理访问上游（`http/XiaochanHttp` 必须经 `executeWithProxy`，直连会 403）。
- 新增可选字段是否向后兼容（distance-3km 模式：`Boolean.TRUE.equals(...)` 默认 false 不改变旧行为）。
- 异常是否走 `BusinessException` + 全局处理器，返回 `BaseResult`。
- 日志是否暴露凭据（PROXY_API_URL vkey / MySQL 密码 / token）。
- application.yaml 改动是否破坏占位符 `${MYSQL_*}`（生产靠 EnvironmentFile 注入）。
- 实体是否该有逻辑删除字段（`@TableLogic deleted`），历史表如 `store_pushed_history` 例外。
- **定时任务复用的 service 方法是否避开 `getByCurrentRequest()`**（见 error-handling.md「定时任务复用的方法不能依赖 HTTP 请求上下文」）——无请求线程里会抛错，必须显式传 `userId`/`auth`。
- **发上游请求前是否做了本地可得的前置校验**——本地能判定的配额/状态（如饭票数为 0）先在 service 短路：落 history + 推送失败，不发请求。一次无效请求 = 一次 WAF 风控暴露，能省则省。查询失败（`null`）应放行而非误杀（宁可多发不可漏抢）。
