# Spring Cloud Gateway

> 前端所有请求的流量入口，本项目 Gateway 端口 80，部署在 mhp-gateway 模块。

---

## 一、Gateway 的作用

```
客户端（浏览器/App）
       ↓ 所有请求
Spring Cloud Gateway（统一入口）
  ├── 鉴权（请求是否带有效 token）
  ├── 路由（把请求转发给对应的微服务）
  ├── 限流（防止某个接口被刷爆）
  ├── 日志（记录每次请求的耗时、状态码）
  └── 跨域（统一处理 CORS）
       ↓ 按路径转发
  mhp-account / mhp-booking / mhp-social
```

**为什么不让每个微服务直接暴露端口？**
- 外部调用 → 必须经过鉴权，鉴权逻辑只需在 Gateway 写一次
- 微服务内部互调（Feign）→ 走内网直连，不经过 Gateway，性能更好
- 微服务地址变化 → 只改 Gateway 路由配置，前端不感知

---

## 二、核心三要素

### 1. Route（路由）

路由是 Gateway 的基本单元，每个路由包含：
- **id**：路由唯一标识
- **uri**：目标地址（转发给谁）
- **predicates**：断言（什么条件才转发）
- **filters**：过滤器（转发前后做什么）

### 2. Predicate（断言）

断言决定"这个请求是否满足条件"。满足所有断言 → 走这条路由。

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: mhp-account-route
          uri: lb://mhp-account          # lb:// 表示通过 Nacos 负载均衡
          predicates:
            - Path=/api/user/**,/api/merchant/**   # 路径断言（最常用）
```

**一个路由写了多个断言，所有都满足才会继续往下传（AND 关系）。**

**常用断言类型**：

```yaml
predicates:
  # 路径匹配（最常用）
  - Path=/api/user/**

  # HTTP 方法
  - Method=GET,POST

  # 请求头
  - Header=token, \w+    # token 请求头存在且匹配正则 \w+

  # 查询参数
  - Query=page, \d+      # 有 page 参数且是数字

  # 时间范围（指定时间后才生效，用于定时上线）
  - After=2026-01-01T00:00:00+08:00[Asia/Shanghai]
  - Before=2026-12-31T23:59:59+08:00[Asia/Shanghai]

  # 来源 IP 白名单
  - RemoteAddr=192.168.1.0/24
```

### 3. Filter（过滤器）

过滤器在请求转发前（pre）或收到响应后（post）执行逻辑。

**过滤器类型**：
- `GatewayFilter`：只对单个路由生效，写在 routes.filters 下
- `GlobalFilter`：对所有路由生效，实现 `GlobalFilter` 接口

---

## 三、路由配置（本项目完整示例）

```yaml
spring:
  cloud:
    gateway:
      routes:
        # 账号服务：用户登录/注册 + 商家信息
        - id: mhp-account
          uri: lb://mhp-account
          predicates:
            - Path=/api/user/**,/api/merchant/**

        # 预约服务：档期 + 预约 + 问卷
        - id: mhp-booking
          uri: lb://mhp-booking
          predicates:
            - Path=/api/schedule/**,/api/booking/**,/api/questionnaire/**

        # 社区服务：动态 + 关注 + 评价 + 投诉 + 消息 + WebSocket
        - id: mhp-social
          uri: lb://mhp-social
          predicates:
            - Path=/api/post/**,/api/follow/**,/api/review/**,
                   /api/complaint/**,/api/message/**,/api/upload/**,/ws/**

      # 全局跨域配置
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowedMethods: "*"
            allowedHeaders: "*"
            allowCredentials: true
```

**为什么内部接口 `/internal/**` 不加路由？**

Gateway 中没有 `/internal/**` 路由，所以从外部通过 Gateway 无法访问内部接口。Feign 调用走服务间直连（`http://mhp-account:8081/internal/...`），不经过 Gateway。

---

## 四、内置 GatewayFilter（单路由过滤器）

```yaml
filters:
  # 去掉路径前缀（转发前把前 N 段路径去掉）
  - StripPrefix=1
  # 例：/api/user/login → 去掉 /api → /user/login

  # 添加请求头
  - AddRequestHeader=X-Source, gateway

  # 添加响应头
  - AddResponseHeader=X-Response-Time, 100ms

  # 设置请求路径
  - RewritePath=/api/(?<segment>.*), /$\{segment}

  # 重定向
  - RedirectTo=302, https://example.com

  # 限流（需要配合 RequestRateLimiter 实现）
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 10    # 每秒补充令牌数
      redis-rate-limiter.burstCapacity: 20    # 令牌桶容量
      redis-rate-limiter.requestedTokens: 1   # 每次请求消耗令牌数
      key-resolver: "#{@ipKeyResolver}"        # 按 IP 限流
```

---

## 五、GlobalFilter（全局过滤器，最重要）

全局过滤器对**所有路由**生效，是实现鉴权、日志、限流的核心扩展点。

### 实现鉴权（本项目方案）

```java
@Component
@Order(-1)  // 数字越小优先级越高，-1 保证最先执行
public class AuthGlobalFilter implements GlobalFilter {

    // 白名单：不需要 token 的路径
    private static final List<String> WHITE_LIST = List.of(
        "/api/user/login",
        "/api/user/register",
        "/ws/"             // WebSocket 握手
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 白名单放行
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // 检查 token
        String token = exchange.getRequest().getHeaders().getFirst("token");
        if (token == null || token.isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 验证 token（这里调用 Sa-Token 或 Redis 验证）
        // 本项目 Sa-Token 的验证放在各微服务的拦截器里，Gateway 只做基础格式检查
        // 避免 Gateway 依赖 Sa-Token，保持 Gateway 轻量

        return chain.filter(exchange);
    }
}
```

### 实现请求日志

```java
@Component
@Order(0)
public class LogGlobalFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            // then() 在响应返回后执行（post 阶段）
            int status = exchange.getResponse().getStatusCode().value();
            long cost = System.currentTimeMillis() - start;
            log.info("[Gateway] {} {} → {} {}ms", method, path, status, cost);
        }));
    }
}
```

### filter() 方法的 Mono 返回值

Gateway 基于 **WebFlux（响应式框架）**，不是普通的 Servlet。方法返回 `Mono<Void>` 而不是 `void`：

```
Mono<Void>：代表一个异步操作（"将来某个时候完成"）
chain.filter(exchange)：把请求传给下一个 filter
.then(...)：下一个 filter 处理完（收到响应）后，执行这里
```

和 Servlet 过滤器的对比：

```java
// Servlet 过滤器（同步）
public void doFilter(request, response, chain) {
    // pre 处理
    chain.doFilter(request, response);  // 同步等待
    // post 处理
}

// WebFlux GlobalFilter（响应式）
public Mono<Void> filter(exchange, chain) {
    // pre 处理
    return chain.filter(exchange)       // 非阻塞，返回 Mono
        .then(Mono.fromRunnable(() -> {
            // post 处理
        }));
}
```

---

## 六、完整请求处理流程

```
客户端请求
       ↓
HttpServer（Netty，非阻塞）
       ↓
DispatcherHandler（WebFlux 的请求分发器）
       ↓
RoutePredicateHandlerMapping
  → 遍历所有 Route 的 Predicate，找到匹配的路由
       ↓
FilteringWebHandler
  → 构建 GlobalFilter 链 + 当前 Route 的 GatewayFilter 链
  → 按 @Order 从小到大排序，依次执行
       ↓
NettyRoutingFilter（最后一个 filter，负责实际转发）
  → 向目标微服务发 HTTP 请求
       ↓
响应逐层返回（filter 链反向执行 post 阶段）
       ↓
响应返回给客户端
```

---

## 七、Gateway + Nacos 动态路由

配置文件中的路由是静态的，修改需要重启 Gateway。如果路由规则存在 Nacos 配置中心，修改 Nacos 即可热更新：

```java
@Component
@RefreshScope  // 配合 Nacos 配置中心实现热刷新
public class DynamicRouteConfig {
    // 在 Nacos 中维护路由规则的 JSON，变更后 Gateway 自动重新加载
}
```

本项目当前用静态路由配置（application.yaml），路由不频繁变化，无需动态路由。

---

## 八、Gateway + Sentinel 限流

```yaml
# application.yaml（mhp-gateway）
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080  # Sentinel 控制台地址
      scg:
        fallback:
          mode: response           # 限流时返回 JSON 而不是 redirect
          response-status: 429
          response-body: '{"code":429,"message":"请求过于频繁，请稍后重试"}'
```

在 Sentinel 控制台的 **API 管理** → **流控规则** 中为路由配置限流规则，无需重启。

---

## 九、常见问题

**Q：Gateway 转发后，目标服务接收到的请求路径是什么？**

默认保留原始路径。如果配了 `StripPrefix=1`，则去掉第一段路径。

```
客户端：GET /api/user/login
Gateway 路由配置：predicates: [Path=/api/user/**]，无 StripPrefix
目标服务收到：GET /api/user/login（路径不变）
```

本项目微服务 Controller 的 `@RequestMapping` 写的是 `/api/xxx`，与 Gateway 路由路径一致，不需要 StripPrefix。

**Q：WebSocket 怎么透传？**

Gateway 自动支持 WebSocket 升级请求透传，只需在路由中匹配 `/ws/**`，无需额外配置。WebSocket 握手时的 HTTP Upgrade 请求会正常转发，之后的 WebSocket 帧也会透传。

**Q：为什么 Gateway 用 WebFlux 而不是 Spring MVC？**

网关是所有流量的入口，需要处理大量并发连接。WebFlux（Reactor + Netty）使用非阻塞 IO，一个线程可以处理多个连接，比 Servlet 模型（一个请求占一个线程）吞吐量高很多。
