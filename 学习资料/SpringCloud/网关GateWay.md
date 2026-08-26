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

### 三种限流方案对比

Gateway 层面有两种限流方式，加上微服务内部 Sentinel，共三种：

| | Gateway 自带 RequestRateLimiter | Sentinel @ Gateway（本项目用） | Sentinel @ 微服务内部 |
|---|---|---|---|
| 限流位置 | Gateway JVM | Gateway JVM | 各微服务 JVM |
| 算法 | 令牌桶（Redis） | 滑动窗口 | 滑动窗口 |
| 规则修改 | 改 YAML 重启 | 控制台热改，无需重启 | 控制台热改，无需重启 |
| 监控面板 | ❌ | ✅ Sentinel Dashboard | ✅ Sentinel Dashboard |
| 能熔断吗 | ❌ | ❌（只限流） | ✅（支持熔断降级） |
| 保护内部 Feign 调用 | ❌ | ❌ | ✅ |
| 粒度 | 按路由/IP | 按路由/API 分组 | 按接口/方法/参数 |

**为什么两层都要配？**

Gateway 限流是粗粒度入口防护，但它有盲区：微服务之间的 Feign 内部调用不经过 Gateway，Gateway 的限流对这部分完全无效。只有微服务内部的 Sentinel 才能保护这条路径：

```
外部请求 → Gateway 限流 → mhp-booking
                               ↓ Feign 内部直调（不过 Gateway）
                          mhp-account（Gateway 限流管不到这里）
```

### Sentinel 嵌入 Gateway 的底层原理

**Sentinel 不是独立软件，是嵌入 JVM 的库。** 向 `mhp-gateway/pom.xml` 加依赖后，Sentinel SDK 就跑在 Gateway 的同一个 JVM 进程里：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
</dependency>
```

```
mhp-gateway 进程（JVM）
├── Spring Cloud Gateway 核心（路由、过滤器链、Netty 转发）
└── Sentinel SDK（同一个 JVM，通过依赖引入）
      ├── SentinelGatewayFilter（自动注册为 GlobalFilter）
      ├── 内存中的规则表（FlowRule：路由 id → QPS 阈值）
      └── 内存中的统计数据（每个路由的 QPS、RT）
             ↕ TCP 心跳（端口 8719）
Sentinel Dashboard（独立进程，localhost:8080）
  → 接收 Gateway 上报的监控数据 → 显示图表
  → 管理员配置规则 → 推送到 Gateway 内存的 FlowRule 表
```

**Dashboard 挂掉不影响限流**，规则已经在 Gateway 内存里了。但 Gateway 重启后规则丢失，生产环境需持久化到 Nacos（见 sentinel.md 第十节）。

### 一次被限流请求的完整链路

```
客户端请求 GET /api/merchant/search
       ↓
Netty 接收 TCP 连接（Gateway 监听 80 端口）
       ↓
DispatcherHandler（WebFlux 的请求派发器）
       ↓
RoutePredicateHandlerMapping → 匹配到 mhp-account 路由
       ↓
FilteringWebHandler 构建过滤器链（按 @Order 执行）：
  ┌─────────────────────────────────────────────────┐
  │ SentinelGatewayFilter（Order 最小，最先执行）   │
  │   资源名 = 路由 id = "mhp-account"              │
  │   查内存 FlowRule → 当前 QPS 是否超阈值？       │
  │   ├── 未超 → 继续往下                           │
  │   └── 已超 → 直接返回 429，后续 filter 不执行   │
  ├─────────────────────────────────────────────────┤
  │ AuthGlobalFilter（鉴权）                         │
  ├─────────────────────────────────────────────────┤
  │ NettyRoutingFilter（实际转发到 mhp-account）     │
  └─────────────────────────────────────────────────┘
```

429 响应内容来自 `sentinel.scg.fallback` 配置：

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080
      scg:
        fallback:
          mode: response           # 直接返回 JSON，不做重定向
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

**Q：Nginx 和 Gateway 的 Netty 是什么关系？**

两者串联，不是替代关系：

```
# 生产环境
客户端 → Nginx:443（SSL 终止）
              ├── location /      → 直接返回 dist/ 静态文件（不进 Java）
              └── location /api/  → proxy_pass → Gateway:80
                                          ↓
                                     Netty 接收 TCP 连接

# 开发环境
浏览器 → Vite dev server:5173（proxy 配置）→ Gateway:80 → Netty 接收
```

Nginx 是操作系统层的反向代理，负责 SSL、静态文件、把 `/api/` 请求转给 Gateway。到了 Gateway 这里，接收 TCP 连接的是 **Netty**（Gateway 进程自己的网络层）。"Netty 监听 80 端口"说的就是 Gateway 进程本身，Nginx 在这之前已经不在链路里了。

**Q：为什么 Gateway 用 WebFlux + Netty，微服务用 Spring MVC + Tomcat？**

Spring MVC（Tomcat）的线程模型是一个请求占一个线程，等待下游响应期间线程阻塞：

```
同时 1000 个请求 → 需要 1000 个线程 → Gateway 作为入口扛不住
```

WebFlux（Netty）用事件循环，少量线程管理所有 IO 事件：

```
少量线程（CPU 核心数 × 2）
  → 同时管理几万个连接
  → 发出转发请求后不等，立刻处理下一个事件
  → 下游响应回来时事件触发，继续处理
```

Gateway 的工作是转发，大量时间在等下游响应，非阻塞模型吞吐量高很多。微服务执行业务逻辑，同步写法更自然，Spring MVC 就够了。

**DispatcherHandler vs DispatcherServlet** 职责完全相同（找处理器 → 执行 → 返回响应），只是一个基于 Servlet API（同步），一个基于 Reactive API（异步）：

```java
// Spring MVC
void service(HttpServletRequest req, HttpServletResponse res) { ... }

// WebFlux（Gateway 用的）
Mono<Void> handle(ServerWebExchange exchange) { return ...; }
```

**请求完整路径（两段技术栈串联）：**

```
客户端请求
       ↓
mhp-gateway（WebFlux + Netty）
  Netty 接收 TCP 连接
  DispatcherHandler 派发
  GlobalFilter 链（鉴权、Sentinel 限流）
  NettyRoutingFilter 发出 HTTP 请求
       ↓ 普通 HTTP（微服务只认 HTTP，不关心上游是 Netty 还是浏览器）
mhp-account（Spring MVC + Tomcat）
  Tomcat 接收 TCP 连接
  DispatcherServlet 派发
  Sa-Token 拦截器（验 token）
  Controller 方法执行
       ↓
响应原路返回
```
