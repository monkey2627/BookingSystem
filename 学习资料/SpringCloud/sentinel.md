# Sentinel 流量治理

> Sentinel 是 Alibaba 开源的流量治理组件，解决微服务在高并发和故障场景下的稳定性问题。

---

## 一、为什么需要 Sentinel？

**服务雪崩**：微服务调用链中，某个服务响应慢或挂掉，导致上游服务请求堆积，最终整个调用链全部崩溃。

```
正常：A → B → C（C 响应 10ms）

C 响应变慢（500ms）：
  A 的线程等 B，B 的线程等 C
  线程池耗尽 → A 无法处理新请求 → A 也崩了 → 雪崩
```

**Sentinel 的三道防线**：

| 问题 | Sentinel 解决方案 |
|------|-----------------|
| 请求量暴增，压垮服务 | 流量控制（限流） |
| 某个下游服务响应慢 | 熔断降级（快速失败，不再等待）|
| 整个机器资源耗尽 | 系统保护（CPU/内存兜底）|

---

## 二、资源和规则

### 资源（Resource）

所有需要保护的代码单元都叫"资源"：

- **所有 Web 接口**（Controller 方法）自动识别为资源，资源名 = 请求路径（如 `/api/merchant/search`）
- **OpenFeign 调用**也自动识别为资源
- **手动声明**：用 `@SentinelResource` 注解

```java
// 自动识别的资源不需要任何注解，直接在 Sentinel 控制台配置规则即可
@GetMapping("/api/merchant/search")
public Result<Page<MerchantVO>> search(...) { ... }
```

### 规则（Rule）

规则决定如何处理资源的流量。规则类型：

| 规则 | 作用 |
|------|------|
| 流量控制（Flow）| 限制请求速率，超过阈值则排队/拒绝 |
| 熔断降级（Degrade）| 检测到异常则自动熔断，快速返回失败 |
| 系统保护（System）| 根据机器整体指标（CPU、QPS）触发 |
| 来源访问控制（Authority）| 白名单/黑名单控制来源 |
| 热点参数限流（Hotspot）| 针对某个参数的高频值单独限流 |

---

## 三、流量控制

流控规则的核心参数：

| 参数 | 说明 |
|------|------|
| 资源名 | 哪个资源（接口路径或 @SentinelResource 的 value）|
| 阈值类型 | QPS（每秒请求数）或并发线程数 |
| 单机阈值 | 超过此值触发流控 |
| 流控效果 | 快速失败 / 预热 / 排队等待 |

### 三种流控效果

**快速失败（默认）**：超过阈值直接拒绝，抛出 `FlowException`。

```
QPS 阈值 = 10：第 11 个请求直接返回错误
```

**预热（Warm Up）**：冷启动时阈值从低到高逐渐升至设定值，避免刚启动时突然大流量打垮服务。

```
设定 QPS = 100，预热时间 = 5 秒：
  0s  → 阈值 33（100/3）
  5s  → 阈值 100（完全预热）
```

**排队等待（Leaky Bucket 漏桶）**：超过阈值的请求不立即拒绝，在队列中等待，匀速处理。

```
QPS = 10，超时等待 = 500ms：
  第 11 个请求等待 100ms（1/10s）后处理
  如果等待时间超过 500ms 才轮到，则拒绝
```

### 流控模式

| 模式 | 说明 |
|------|------|
| 直接 | 对当前资源直接限流 |
| 关联 | 资源 B 触发阈值时，限流资源 A（场景：写接口压力大时，限制读接口）|
| 链路 | 只统计从某个入口进来的请求（精细化控制）|

---

## 四、熔断降级

当某个资源出现异常（慢调用/错误率高），Sentinel 自动进入熔断状态，后续请求不再等待下游，直接快速失败。熔断时间到后，尝试放行少量请求探测是否恢复。

### 熔断策略

**慢调用比例（SLOW_REQUEST_RATIO）**：

```
设定：最大 RT=200ms，比例阈值=50%，熔断时长=10s，最小请求数=5

含义：统计周期内，如果响应时间>200ms 的请求超过 50%（且总请求>=5），
     触发熔断，熔断 10 秒
```

**异常比例（ERROR_RATIO）**：

```
设定：异常比例=50%，熔断时长=10s

含义：异常请求数/总请求数 > 50%，触发熔断
```

**异常数（ERROR_COUNT）**：

```
设定：异常数=5，熔断时长=10s

含义：统计周期内异常数超过 5 次，触发熔断
```

### 熔断状态转换

```
正常（CLOSED）
       ↓ 触发熔断条件
熔断（OPEN）  ——————→ 请求直接失败
       ↓ 熔断时长到期
半开（HALF_OPEN）—— 放行一个探测请求
       ├── 探测请求成功 → 恢复正常（CLOSED）
       └── 探测请求失败 → 继续熔断（OPEN）
```

---

## 五、@SentinelResource 注解

用于**手动声明**一个资源（非 Web 接口，如 Service 内部方法）。

```java
@Service
public class MerchantServiceImpl {

    @SentinelResource(
        value = "getMerchantDetail",      // 资源名（在 Sentinel 控制台可见）
        blockHandler = "getMerchantDetailBlock",   // 流控/熔断时调用的方法
        fallback = "getMerchantDetailFallback"     // 业务异常时调用的方法
    )
    public MerchantVO getDetail(Long merchantId) {
        // 正常业务逻辑
        return ...;
    }

    // blockHandler：必须是 public，参数与原方法相同 + 末尾加 BlockException
    // 触发时机：Sentinel 规则触发（流控/熔断），不是业务异常
    public MerchantVO getMerchantDetailBlock(Long merchantId, BlockException ex) {
        log.warn("getMerchantDetail 被流控，merchantId={}", merchantId);
        return new MerchantVO();  // 返回默认数据（降级兜底）
    }

    // fallback：处理业务代码抛出的异常
    // 触发时机：原方法抛出 Throwable（但不含 BlockException）
    public MerchantVO getMerchantDetailFallback(Long merchantId, Throwable ex) {
        log.error("getMerchantDetail 业务异常，merchantId={}", merchantId, ex);
        return new MerchantVO();
    }
}
```

**blockHandler vs fallback 区别**：

| | blockHandler | fallback |
|---|---|---|
| 触发条件 | Sentinel 规则触发（QPS 超限、熔断）| 业务代码抛出异常（如 NullPointerException）|
| 参数 | 原参数 + `BlockException` | 原参数 + `Throwable` |
| 用途 | 返回降级数据 | 异常兜底、记录错误 |

**使用注解时的切面说明**：

Sentinel 通过 AOP（切面编程）处理 `@SentinelResource`：
- Spring 创建该 Bean 时发现 `@SentinelResource` 注解
- 自动为方法创建 AOP 代理
- 每次调用时，代理先向 Sentinel 申请资源许可
- 如果被拒绝 → 调用 blockHandler
- 如果业务抛异常 → 调用 fallback

---

## 六、整合 Spring Cloud Gateway 实现网关限流

```yaml
# mhp-gateway/application.yaml
spring:
  cloud:
    gateway:
      routes:
        - id: mhp-account
          uri: lb://mhp-account
          predicates:
            - Path=/api/user/**,/api/merchant/**
    sentinel:
      transport:
        dashboard: localhost:8080   # Sentinel 控制台
      scg:
        fallback:
          mode: response
          response-status: 429
          response-body: '{"code":429,"msg":"请求频率超限，请稍后重试"}'
```

配置后，在 Sentinel 控制台的 **API 管理** 中可以对网关路由配置限流规则（按路由 id）。网关限流不依赖 `@SentinelResource`，规则是基于路由 id 的。

---

## 七、整合 OpenFeign 实现服务降级

详见 nacos和OpenFeign.md 中的 Fallback 部分。核心：

```java
// 1. 实现 FeignClient 接口（降级逻辑）
@Component
public class AccountFeignClientFallback implements AccountFeignClient {
    @Override
    public Result<UserDTO> getUser(Long id) {
        return Result.ok(null);  // mhp-account 不可用时，返回 null 用户
    }
}

// 2. @FeignClient 指定 fallback
@FeignClient(name = "mhp-account", fallback = AccountFeignClientFallback.class)
public interface AccountFeignClient { ... }

// 3. 开启 Feign + Sentinel 整合
# application.yaml
spring.cloud.openfeign.sentinel.enabled: true
```

---

## 八、系统保护

系统保护是最粗粒度的保护，当整台机器的整体指标超标时，拒绝新请求：

| 指标 | 说明 |
|------|------|
| LOAD（仅 Linux）| 系统 load1，超过阈值则限流 |
| CPU 使用率 | CPU 超过阈值（如 80%）限流 |
| 平均 RT | 所有请求的平均响应时间 |
| 入口 QPS | 所有入口 QPS 总和 |
| 并发线程数 | 所有线程数总和 |

系统保护是最后一道防线，通常配合流控规则一起使用。

---

## 九、热点参数限流

对**特定参数的特定值**进行限流。

**场景**：商家详情页，大量用户同时访问同一个热门商家（merchantId=1），单独对这个 id 限流，不影响其他商家。

```java
@GetMapping("/api/merchant/{id}")
@SentinelResource(value = "getMerchant", blockHandler = "getMerchantBlock")
public Result<MerchantVO> getDetail(@PathVariable Long id) {
    return Result.ok(merchantService.getDetail(id));
}
```

在 Sentinel 控制台配置热点规则：
- 资源名：`getMerchant`
- 参数索引：0（第一个参数 id）
- 单机阈值：100（QPS）
- 参数例外项：可以为某个 id（如 id=1）设置更高的阈值

---

## 十、规则持久化（生产环境必须做）

默认情况下，Sentinel 规则存在内存中，**重启后丢失**。生产环境需要配置持久化到 Nacos：

```yaml
spring:
  cloud:
    sentinel:
      datasource:
        flow-rule:              # 流控规则
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: ${spring.application.name}-flow-rules
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: flow
        degrade-rule:           # 熔断规则
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: ${spring.application.name}-degrade-rules
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: degrade
```

持久化后，在 Nacos 中直接修改配置，Sentinel 自动热加载规则，不需要通过 Sentinel 控制台操作。

---

## 十一、Sentinel 控制台

控制台是一个独立的 Spring Boot 应用（sentinel-dashboard.jar），需要单独运行：

```bash
java -jar sentinel-dashboard-1.8.x.jar --server.port=8080
```

**微服务连接控制台**：
```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080  # 控制台地址
        port: 8719                 # 微服务与控制台通信的本地端口（心跳）
```

**控制台功能**：
- 实时监控：QPS、RT、线程数、异常数
- 流控规则、熔断规则管理（可视化配置）
- 热点规则配置
- API 管理（Gateway 路由限流）

**注意**：控制台只是可视化管理界面，规则下发到微服务内存中，重启丢失（除非配置持久化）。
