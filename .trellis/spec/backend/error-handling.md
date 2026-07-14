# Error Handling

> 错误处理约定。

---

## Overview

统一用自定义 `BusinessException` + 全局异常处理器，Controller **不写 try/catch**，所有异常转成 `BaseResult.error(msg)` 返回（HTTP 200）。

---

## Error Types

- `BusinessException`（`config` 包下，**非 `exception` 包**）extends `RuntimeException`，字段 `message` + `code`（默认 500）。
  - 构造：`new BusinessException(String)` 或 `new BusinessException(Integer code, String message)`。
  - 用 401 code 表示未授权（如 `throw new BusinessException(401, "用户不存在")`）。

---

## Error Handling Patterns

- **Service / HTTP 层**：直接 `throw new BusinessException("消息")`，不捕获。
  ```java
  // service/impl
  if (user == null) throw new BusinessException(401, "用户不存在");
  ```
- **Controller 层**：不写 try/catch，直接 `return BaseResult.ok(data)`。异常由全局处理器捕获。
  ```java
  @PostMapping("/config")
  public BaseResult<Void> save(@RequestBody @Valid MonitorConfigDTO dto) {
      service.save(dto);
      return BaseResult.ok();
  }
  ```
- **tasks 层**：`BaseTask.runSingle` 内部 try/catch/finally 捕获异常并记录到 `TaskExecHistoryEntity`，**不向外抛**（定时任务不因单次失败崩溃）。
- **HTTP 工具层**（`XiaochanHttp`）：上游非 2xx 抛 `BusinessException("状态码错误:" + status)`；代理不可用抛 `BusinessException("代理不可用，无法请求小蚕网关")`。

---

## API Error Responses

- 全局处理器 `GlobalResultExceptionHandler`（`@ControllerAdvice @ResponseBody @Priority(1)`）处理：`BusinessException`、`MethodArgumentNotValidException`、`ConstraintViolationException`、`BindException`、`NoResourceFoundException`、通用 `Exception`。
- **所有异常统一返回 HTTP 200**（处理器强制 `setResponseStatus(HttpStatus.OK)`）+ `BaseResult.error(msg)`。
- 统一返回结构 `BaseResult<T>`：`Boolean success`、`Integer code`、`String msg`、`T data`。成功 code=200，错误 code=500。
  - 静态工厂：`BaseResult.ok()` / `BaseResult.ok(data)` / `BaseResult.error(msg)`。
  - `getSuccess()`：success 为 null 时按 `code==200` 判断。
- 日志级别：`BusinessException` 用 `log.warn`，校验异常用 `log.warn`，通用 `Exception` 用 `log.error`（带异常栈）。

---

## Common Mistakes

- **不要在 Controller 写 try/catch**——交给全局处理器，否则破坏统一返回格式。
- `BusinessException` 放 `config` 包（既有事实），新增异常类遵循同位置，勿建 `exception` 包破坏一致性。
- 参数校验：方法参数用 `@Valid`（非 `@Validated`）于 `@RequestBody`；`@RequestParam`/`@PathVariable` 校验用 `@NotBlank(message="...")` 等，类级可加 `@Validated` 触发。
- 当前用户获取：`UserService.getByCurrentRequest()` 从 `RequestContextHolder` 读 header `token` 查库——**无 Spring Security、无拦截器鉴权**，token 校验在 service 层手动做。

---

## Gotcha: 定时任务复用的方法不能依赖 HTTP 请求上下文

> **Warning**: 被 `@Scheduled` / `TaskScheduler` 调起的 service 方法运行在**无 HTTP 请求**线程里，`RequestContextHolder` 为空。此时调用 `UserService.getByCurrentRequest()` 会拿不到当前用户并抛错。

`grab` 模块的 `doGrab(config, triggerType)` 同时服务手动触发（有 HTTP 上下文）与 CRON/ONESHOT 触发（无上下文）。若其内部校验子逻辑走 `getByCurrentRequest()`，定时抢单会直接失败。

**反模式**（饭票校验初版差点踩到）：
```java
// doGrab 定时场景下 getByCurrentRequest() 抛错 → 抢单前校验失败
private Integer getTicketCount(Integer loginStateId) {
    GrabAuth auth = resolveAuth(loginStateId); // 内部调 userService.getByCurrentRequest()
    return countByAuth(auth);
}
```

**正确做法**：抽一个**不依赖 HTTP 上下文**的私有方法，直接接收已构造好的 `GrabAuth` / `Entity`；只有对外公共方法（Controller 调用、有 HTTP 上下文）才走 `getByCurrentRequest()`。
```java
// 对外（Controller，有 HTTP 上下文）：走 resolveAuth → getByCurrentRequest
@Override
public Integer getTicketCount(Integer loginStateId) {
    return countTicketByAuth(resolveAuth(loginStateId));
}
// 对内（doGrab 定时场景，已持有 auth）：直接传 auth，绕开 HTTP 上下文
private Integer countTicketByAuth(GrabAuth auth) { /* 翻页统计 */ }
```

**判据**：写任何被定时任务/调度器复用的 service 方法前，先问"它在无请求线程里还能跑吗？"——能拿到 `userId`/`auth` 就显式传参，绝不隐式从 `RequestContextHolder` 取。
