# OpenFeign 系统笔记

OpenFeign 是**声明式的 REST 客户端**：写一个接口 + 注解，框架自动生成实现类（基于 JDK 动态代理），在内部发 HTTP 请求到目标服务。它本身不知道 Nacos 是什么——Spring Cloud 通过 `FeignBlockingLoadBalancerClient` 在中间桥接，把服务名解析为真实 IP:Port。

---

## 一、与 Nacos 注册中心的配合

理解这一章，是理解"为什么 `@FeignClient(name="mhp-account")` 能直接发出请求"的关键。

### 1.1 Provider 侧：服务如何注册到 Nacos

```
mhp-account 启动
    ↓
spring-cloud-starter-alibaba-nacos-discovery 自动配置生效
NacosAutoServiceRegistration 监听 WebServerInitializedEvent（Tomcat 启动完成后触发）
    ↓
向 Nacos 发送注册请求：POST /nacos/v1/ns/instance
  serviceName = "mhp-account"   ← 取自 spring.application.name
  ip          = "10.2.0.6"      ← 本机 IP（自动探测）
  port        = 8081             ← server.port
  metadata    = { "preserved.register.source": "SPRING_CLOUD" }
    ↓
Nacos 存储实例，启动心跳维护（每 5 秒一次）
```

配置关键项：

```yaml
spring:
  application:
    name: mhp-account      # 注册到 Nacos 的服务名，Consumer @FeignClient(name=) 必须与此一致
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
```

开启服务发现（主启动类）：

```java
@SpringBootApplication
@EnableDiscoveryClient   // 让当前服务能查询其他微服务的实例列表
public class AccountApplication { ... }
```

> Spring Cloud Commons 规范：无论用 Nacos、Eureka 还是 Consul，此注解都适用。

### 1.2 Consumer 侧：OpenFeign 如何发现并调用服务

OpenFeign 调用的完整链路：

```
feignClient.getUser(id)
    ↓ JDK 动态代理拦截，解析注解构建 RequestTemplate
    ↓
FeignBlockingLoadBalancerClient.execute()
    ↓
BlockingLoadBalancerClient.choose("mhp-account")
    → 查本地缓存（从 Nacos 同步的实例列表）
    → RoundRobin 选出一个实例：{ ip: 10.2.0.6, port: 8081 }
    ↓
把 lb://mhp-account/internal/user/123
替换为 http://10.2.0.6:8081/internal/user/123
    ↓
HttpClient5 发送真实 HTTP/1.1 请求
```

这就是为什么 `@FeignClient(name = "mhp-account")` 里写服务名而不是 IP——服务名是 Nacos 里的 key，OpenFeign 通过 LoadBalancer 自动把它解析成真实地址。

**对比：不用 OpenFeign 时，你需要手写这些：**

```java
// DiscoveryClient 手动方式（理解底层用，实际开发不用）
List<ServiceInstance> instances = discoveryClient.getInstances("mhp-booking");
ServiceInstance instance = instances.get(0);   // 手动"负载均衡"
String url = "http://" + instance.getHost() + ":" + instance.getPort() + "/internal/booking/" + id;
return restTemplate.getForObject(url, BookingDTO.class);

// OpenFeign 方式（实际开发）
BookingDTO booking = bookingFeignClient.getById(id);   // 一行搞定
```

| | 手动 DiscoveryClient | OpenFeign |
|---|---|---|
| 代码量 | 多（手动拼 URL）| 少（只写接口）|
| 负载均衡 | 自己实现 | 自动（Spring Cloud LoadBalancer）|
| 底层 | 相同（都从 Nacos 拿地址再发 HTTP）| 相同 |

### 1.3 @LoadBalanced 的底层原理

`@LoadBalanced` 是另一种接入方式——用 RestTemplate 时使用，原理和 OpenFeign 相同，都走 Spring Cloud LoadBalancer：

```java
@Bean
@LoadBalanced
public RestTemplate restTemplate() {
    return new RestTemplate();
}

// 调用时用服务名代替 IP
restTemplate.getForObject("http://mhp-booking/internal/booking/" + id, BookingDTO.class);
```

`@LoadBalanced` 本质上是一个 `@Qualifier` 标记注解。Spring 启动时，`LoadBalancerAutoConfiguration` 检测到被标记的 RestTemplate，自动向其拦截器链注入 `LoadBalancerInterceptor`：

```
调用 restTemplate.getForObject("http://mhp-booking/...")
    ↓
LoadBalancerInterceptor.intercept() 触发
    1. 解析服务名：mhp-booking
    2. LoadBalancerClient.choose("mhp-booking") → 查 Nacos 缓存 → 选实例
    3. 替换 URL 为真实地址
    4. 发送真实请求
```

**本项目用 OpenFeign，不用 @LoadBalanced RestTemplate。** OpenFeign 内置了同等能力，`@FeignClient(name=)` 的 name 就是服务名，无需 `@LoadBalanced` 注解。

两种方式的负载均衡策略配置在同一处：

```yaml
spring:
  cloud:
    loadbalancer:
      configurations: random  # 默认轮询 RoundRobin，改为随机
```

### 1.4 实例缓存详解

Consumer 不是每次调用都去 Nacos 查询，而是本地缓存 + 订阅推送：

```
[Nacos SDK 内层缓存]（ServiceInfoHolder）
  → 通过 UDP 推送 + 长轮询从 Nacos 获取，Map<serviceName, ServiceInfo>

[Spring Cloud LoadBalancer 外层缓存]（CachingServiceInstanceListSupplier）
  → 从内层缓存再取一次，加 TTL（默认 35 秒）
  → 避免每次请求都穿透到内层
```

缓存的不只是 IP，而是完整的 ServiceInstance 对象：

```
ServiceInstance {
  serviceId = "mhp-booking"
  host      = "192.168.1.20"
  port      = 8082
  metadata  = { "version": "1.0" }
  healthy   = true
}
```

### 1.5 Nacos 宕机后的能力边界

两层缓存都在 JVM 内存中，Nacos 宕机不影响它们：

| 能力 | Nacos 宕机后 |
|------|-------------|
| 现有实例的服务发现 | ✅ 从缓存继续提供 |
| 负载均衡选实例 | ✅ 在缓存列表上轮询 |
| 发现新上线的实例 | ❌ 无法感知 |
| 摘除已下线的实例 | ❌ 缓存不更新 |
| 新服务注册 | ❌ 注册请求会失败 |

"已下线实例不会被摘除"的风险：负载均衡仍可能打到已崩溃的实例。兜底方案是 **Sentinel 熔断**——连续失败触发熔断，不再发请求到这个实例。

**结论**：Nacos 短暂宕机对现有调用链路影响很小；长时间宕机主要风险是实例列表失准，不是整个系统崩溃。

---

## 二、核心用法

### 2.1 开启 Feign

```java
@SpringBootApplication
@EnableFeignClients(basePackages = "com.mhp.booksystem.feign")
// 本项目 FeignClient 在 mhp-common 模块，必须显式指定包名
public class SocialApplication { ... }
```

### 2.2 @FeignClient 注解详解

```java
@FeignClient(
    name = "mhp-account",   // Nacos 中的服务名，用于服务发现
    // url = "http://localhost:8081"  // 直接指定地址，绕过 Nacos（调试用）
    fallback = AccountFeignClientFallback.class
)
public interface AccountFeignClient {

    @GetMapping("/internal/user/{id}")
    Result<UserDTO> getUser(@PathVariable("id") Long id);

    @PostMapping("/internal/merchant/{id}/score")
    Result<Void> updateMerchantScore(
        @PathVariable("id") Long id,
        @RequestBody MerchantScoreUpdateDTO dto
    );

    // @RequestParam：查询参数，拼在 URL 后面 ?ids=1,2,3
    @GetMapping("/internal/user/batch")
    Result<List<UserDTO>> batchGetUsers(@RequestParam("ids") List<Long> ids);

    // @RequestHeader：放在请求头中
    @GetMapping("/some/api")
    Result<Void> someApi(@RequestHeader("token") String token);
}
```

**注解逻辑与 Spring MVC 相反**：
- Spring MVC Controller 上的注解：**接收**请求（我提供什么路径）
- Feign 接口上的注解：**发送**请求（我向谁发什么路径）

### 2.3 配置项

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:                    # 对所有 Feign 客户端生效
            connect-timeout: 3000     # 建立 TCP 连接超时（ms）
            read-timeout: 10000       # 等待响应超时（ms）
            logger-level: BASIC       # 日志级别：NONE/BASIC/HEADERS/FULL
          mhp-account:                # 仅对 mhp-account 生效（覆盖 default）
            read-timeout: 5000
      httpclient:
        hc5:
          enabled: true              # 启用 Apache HttpClient5（性能更好）
```

| 日志级别 | 输出内容 |
|---------|---------|
| `NONE` | 不输出（生产用）|
| `BASIC` | 请求方法 + URL + 状态码 + 耗时 |
| `HEADERS` | BASIC + 请求头/响应头 |
| `FULL` | HEADERS + 请求体/响应体（调试用）|

### 2.4 重试机制

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            retryer: feign.Retryer.Default  # 重试 5 次，初始间隔 100ms，最大 1s
```

**注意**：重试只用于幂等请求（GET）。POST/PUT 等写操作不应开启重试，否则可能产生重复数据。

---

## 三、高级特性

### 3.1 拦截器（RequestInterceptor）

`feign.RequestInterceptor` 是 OpenFeign 自己的接口（来自 `feign-core`，与 Spring 无关）。加 `@Component` 即可自动生效——`FeignClientsConfiguration` 构建 `Feign.Builder` 时会从 Spring 容器收集所有 `RequestInterceptor` Bean：

```java
// FeignClientsConfiguration 内部（简化）
@Autowired(required = false)
private List<RequestInterceptor> requestInterceptors = new ArrayList<>();

@Bean
public Feign.Builder feignBuilder() {
    return Feign.builder().requestInterceptors(requestInterceptors);
}
```

`apply()` 被调用时，请求已部分构建完毕（注解已解析、参数已填充）：

```java
@Component
public class FeignTokenInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // template 此时已有：HTTP 方法、路径（{id} 已替换）、body（已序列化）
        template.url();      // 如 /internal/user/123
        template.method();   // 如 GET

        // 最常用：透传 token
        String token = RequestContextHolder.getRequestAttributes() != null
            ? (String) RequestContextHolder.getRequestAttributes()
                .getAttribute("token", RequestAttributes.SCOPE_REQUEST)
            : null;
        if (token != null) {
            template.header("token", token);
        }
    }
}
```

### 3.2 Encoder / Decoder 自定义

默认行为：请求体用 Jackson 序列化为 JSON，响应体用 Jackson 反序列化为返回值类型。

**方式一：全局替换**

```java
@Configuration
public class GlobalFeignConfig {
    @Bean
    public Encoder feignEncoder(ObjectMapper objectMapper) {
        return new JacksonEncoder(objectMapper);
    }
    @Bean
    public Decoder feignDecoder(ObjectMapper objectMapper) {
        return new JacksonDecoder(objectMapper);
    }
}
```

**方式二：只对某个 FeignClient 生效**

```java
public class AccountFeignConfig {   // 不加 @Configuration，避免被全局扫描
    @Bean
    public Decoder customDecoder() { return new MyCustomDecoder(); }
}

@FeignClient(name = "mhp-account", configuration = AccountFeignConfig.class)
public interface AccountFeignClient { ... }
```

**实用场景：自动解包 `Result<T>` 包装层**

本项目每个内部接口都返回 `Result<T>`，调用方每次都要 `.getData()` 解包：

```java
// 现在：每次都要解包
Result<UserDTO> result = accountFeignClient.getUser(id);
UserDTO user = result.getData();
```

自定义 Decoder 自动提取 data 字段：

```java
public class ResultUnwrappingDecoder implements Decoder {
    private final ObjectMapper objectMapper;

    public ResultUnwrappingDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        String body = Util.toString(response.body().asReader(StandardCharsets.UTF_8));
        JsonNode data = objectMapper.readTree(body).get("data");
        return objectMapper.readValue(
            data != null ? data.toString() : "null",
            objectMapper.constructType(type)
        );
    }
}
```

接口即可直接声明真实类型：

```java
@FeignClient(name = "mhp-account", configuration = AccountFeignConfig.class)
public interface AccountFeignClient {
    @GetMapping("/internal/user/{id}")
    UserDTO getUser(@PathVariable("id") Long id);   // 直接返回 UserDTO
}
```

> 本项目保持 `Result<T>` 返回，因为 code/msg 字段在异常处理时有用（判断 code 是否 200）。

### 3.3 整合 Sentinel 熔断降级

```java
// 1. 定义 Fallback 类
@Component
public class AccountFeignClientFallback implements AccountFeignClient {
    @Override
    public Result<UserDTO> getUser(Long id) {
        return Result.ok(null);   // mhp-account 超时或挂了，返回兜底数据
    }
    @Override
    public Result<Void> updateMerchantScore(Long id, MerchantScoreUpdateDTO dto) {
        log.error("mhp-account 不可用，merchantId={}", id);
        return Result.ok();
    }
}

// 2. @FeignClient 指定 fallback
@FeignClient(name = "mhp-account", fallback = AccountFeignClientFallback.class)
public interface AccountFeignClient { ... }
```

```yaml
spring:
  cloud:
    openfeign:
      sentinel:
        enabled: true
```

---

## 四、内部实现原理（面试常考）

### 4.1 动态代理生成过程

```
@EnableFeignClients 扫描 @FeignClient 接口
    ↓
FeignClientFactoryBean.getObject() 被调用
    ↓
Feign.Builder 构建代理：
  encoder + decoder + interceptors（拦截器链）
  + delegate = FeignBlockingLoadBalancerClient（桥接 Nacos 的那一层）
    ↓
JDK Proxy.newProxyInstance() 生成接口的代理对象
    ↓
代理对象注入 Spring 容器（@Autowired 拿到的就是这个代理）
```

### 4.2 FeignBlockingLoadBalancerClient 是什么

它不是动态代理，而是 Spring Cloud 插入的普通桥接类：

```
你声明的接口 AccountFeignClient
    ↓ 被
JDK 动态代理包裹（$Proxy123）← 实现了你的接口，这才是"代理"
  内部持有 SynchronousMethodHandler
    ↓ 持有的 HTTP 客户端是
FeignBlockingLoadBalancerClient    ← 普通类，所有 @FeignClient 共用同一个单例
  内部持有 BlockingLoadBalancerClient（Spring Cloud LoadBalancer）
    ↓
HttpClient5（真正发 TCP 请求）
```

| | JDK 动态代理 | FeignBlockingLoadBalancerClient |
|---|---|---|
| 是什么 | 实现了你的业务接口的代理对象 | 普通的 `feign.Client` 实现类 |
| 职责 | 把方法调用翻译成 HTTP 请求 | 把服务名换成真实 IP:Port |
| 每个 @FeignClient 独立 | ✅ 每个接口一个专属实例 | ❌ 所有 @FeignClient 共用单例 |
| 来自哪个包 | `feign-core`（OpenFeign 官方）| `spring-cloud-openfeign-core`（Spring Cloud）|

`FeignBlockingLoadBalancerClient` 存在的原因：OpenFeign 本身不知道 Nacos，Spring Cloud 在中间插入这一层来桥接两者。

### 4.3 一次完整调用流程

```
调用 feignClient.getUser(id)
    ↓ 代理的 InvocationHandler.invoke() 触发
    ↓
SynchronousMethodHandler.invoke()：
  1. 读取方法元数据（注解解析的请求模板）
  2. 解析参数，填充 RequestTemplate（URL、body、query）
  3. 执行所有 RequestInterceptor.apply()（如加 token header）
  4. FeignBlockingLoadBalancerClient：
       → 从 Nacos 缓存取实例列表
       → 负载均衡选一个实例（10.2.0.6:8081）
       → 替换服务名为真实地址
  5. HttpClient 发送 HTTP 请求
  6. 检查状态码（4xx/5xx 抛异常）
  7. Decoder 把响应 JSON 反序列化为返回值类型
  8. 返回给调用方（若有 fallback 且调用失败，走 fallback）
```

---

## 附录：Nacos 配置中心

配置中心与 OpenFeign 无直接关联，单独作为附录供参考。

### 核心概念三级结构

```
Namespace（命名空间）—— 区分环境（dev/test/prod）
  └── Group（组）—— 区分项目或模块（DEFAULT_GROUP / MHP_GROUP）
        └── DataId（配置文件名）—— mhp-account.yaml
```

### 接入配置中心

```yaml
spring:
  config:
    import: optional:nacos:mhp-account.yaml   # optional: 表示 Nacos 不可达时不报错
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yaml
```

优先级：**Nacos 配置中心 > 本地 application.yaml**（同一 key，Nacos 覆盖本地）

### 动态刷新：@RefreshScope

Spring Cloud Nacos 启动时开启长轮询后台线程：

```
向 Nacos 发 POST /v1/cs/configs/listener（携带当前所有 DataId 的 MD5）
Nacos 挂起请求，最多等 30 秒：
  ├── 无变化 → 返回空，下一轮继续
  └── 配置变化 → 立刻响应，返回变化的 DataId
      ↓
客户端重新拉取配置 → 发布 RefreshEvent
      ↓
ContextRefresher.refresh()：更新 Environment，把 @RefreshScope Bean 标记为"脏"
      ↓
下次访问该 Bean → Spring 销毁旧实例，用新配置值重新创建
```

```java
@RestController
@RefreshScope   // 配置变化后，Bean 延迟重建，@Value 字段自动取新值
public class SomeController {
    @Value("${some.config:default}")
    private String someConfig;
}
```

### configService.addListener（手动监听）

```java
@PostConstruct
public void init() throws NacosException {
    configService.addListener("mhp-account.yaml", "DEFAULT_GROUP", new Listener() {
        @Override
        public Executor getExecutor() { return null; }

        @Override
        public void receiveConfigInfo(String configInfo) {
            // 拿到完整新配置字符串，自定义处理（如清缓存）
            log.info("配置已更新：{}", configInfo);
        }
    });
}
```

| | @RefreshScope | addListener |
|---|---|---|
| 触发方式 | Spring Cloud 自动（RefreshEvent）| Nacos SDK 回调 |
| 你写的代码 | 只需加注解 | 手动注册 + 实现回调 |
| 刷新内容 | 整个 Bean 重建，所有 @Value 更新 | 你在回调里自定义 |
| 适合场景 | @Value 字段需要跟配置变化 | 配置变化还需清缓存、通知等副作用 |

两者独立，可以单独用，也可以组合用（@Value 自动刷新 + 同时清 Redis 缓存）。

**本项目暂未使用**：当前配置（DB 地址、Redis 地址）是运维级配置，不需要在线热更新。
