# Nacos + OpenFeign

> Nacos 用于作为微服务的注册中心和配置中心。

---

## 一、Nacos 注册中心

### 快速接入

Nacos 3.0 强制要求用 MySQL 作为数据库，并要求开启鉴权。

```yaml
# application.yaml（各微服务）
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848  # Nacos 地址
```

引入依赖后，微服务启动就自动注册到 Nacos。

### @EnableDiscoveryClient

放在主启动类上，开启服务发现功能（让当前服务能查询其他微服务的实例列表）。

```java
@SpringBootApplication
@EnableDiscoveryClient
public class AccountApplication { ... }
```

> Spring Cloud Commons 规范：无论用 Nacos、Eureka 还是 Consul，此注解都适用。

### DiscoveryClient 底层原理（了解即可，实际用 Feign 封装好的 API）

```java
@Autowired
private DiscoveryClient discoveryClient;

public void callRemote(Long id) {
    // 1. 从 Nacos 查服务实例列表
    List<ServiceInstance> instances = discoveryClient.getInstances("mhp-booking");
    // 2. 手动实现负载均衡（这里偷懒取第一个）
    ServiceInstance instance = instances.get(0);
    // 3. 拼 URL
    String url = "http://" + instance.getHost() + ":" + instance.getPort() + "/internal/booking/" + id;
    // 4. 手动发 HTTP 请求
    return restTemplate.getForObject(url, BookingDTO.class);
}
```

**OpenFeign 方式（实际开发用）**：
```java
@FeignClient(name = "mhp-booking")
public interface BookingFeignClient {
    @GetMapping("/internal/booking/{id}")
    BookingDTO getById(@PathVariable Long id);
}

BookingDTO booking = bookingFeignClient.getById(id);
```

**两者对比**：

| | DiscoveryClient | OpenFeign |
|---|---|---|
| 代码量 | 多（手动拼 URL）| 少（只写接口）|
| 负载均衡 | 自己实现 | 自动（Spring Cloud LoadBalancer）|
| 使用场景 | 学习底层原理 | 实际开发 |
| 底层 | 相同（都从 Nacos 拿地址再发 HTTP）| 相同 |

---

### @LoadBalanced 到底配了什么？为什么能实现负载均衡？

`@LoadBalanced` 是 Spring Cloud 的注解，本质上只是一个 `@Qualifier`（标记注解），真正起作用的是 Spring Cloud 的自动配置机制：

```java
// 配置类中声明一个带 @LoadBalanced 的 RestTemplate
@Bean
@LoadBalanced
public RestTemplate restTemplate() {
    return new RestTemplate();
}

// 调用时用服务名代替 IP：
restTemplate.getForObject("http://mhp-booking/internal/booking/" + id, BookingDTO.class);
```

**背后发生了什么：**

```
Spring 启动时
  ↓
LoadBalancerAutoConfiguration 检测到：
  - classpath 有 spring-cloud-loadbalancer
  - 容器中存在被 @LoadBalanced 标记的 RestTemplate bean
  ↓
自动向该 RestTemplate 的拦截器列表中注入 LoadBalancerInterceptor
  ↓
调用 restTemplate.getForObject("http://mhp-booking/...")
  ↓
LoadBalancerInterceptor.intercept() 被触发：
  1. 从 URL 中解析服务名：mhp-booking
  2. 调用 LoadBalancerClient.choose("mhp-booking")
       → 查本地缓存的实例列表（从 Nacos 同步来的）
       → 按负载均衡策略（默认轮询）选出一个 ServiceInstance（含 IP+Port）
  3. 把 URL 中的服务名替换为真实地址：http://192.168.1.20:8082/internal/booking/...
  4. 发送真实 HTTP 请求
```

**本项目（MHP）用的不是 @LoadBalanced RestTemplate，而是 OpenFeign：**

OpenFeign 不需要 `@LoadBalanced` 注解，它内置了负载均衡。`@FeignClient(name = "mhp-booking")` 中的 `name` 就是服务名，OpenFeign 在构建代理时会包装一个 `FeignBlockingLoadBalancerClient`，每次调用时自动走 Spring Cloud LoadBalancer 选实例，效果和 `@LoadBalanced RestTemplate` 完全一样。

**两种方式的负载均衡配置（application.yaml）都是同一个地方：**

```yaml
spring:
  cloud:
    loadbalancer:
      configurations: random  # 改成随机策略（默认是轮询 RoundRobin）
```

---

### 实例缓存详解

#### 缓存的是什么？

缓存的不只是 IP 地址，而是完整的 `ServiceInstance` 对象：

```
ServiceInstance {
  serviceId = "mhp-booking"
  host      = "192.168.1.20"       // IP 地址
  port      = 8082                  // 端口
  metadata  = { "version": "1.0" } // 元数据
  healthy   = true                  // 健康状态
}
```

这些数据存在两层缓存里：

```
[Nacos SDK 内层缓存]（ServiceInfoHolder）
  → Nacos 客户端维护，通过 UDP 推送 + 长轮询从 Nacos 服务端获取
  → Map<serviceName, ServiceInfo>（包含实例列表）

[Spring Cloud LoadBalancer 外层缓存]（CachingServiceInstanceListSupplier）
  → 从 Nacos SDK 的内层缓存再取一次，加 TTL（默认 35 秒）
  → 避免每次请求都查询内层缓存，减少开销
```

#### Nacos 宕机后还能工作吗？

**能，但有限制**。两层缓存都保留在 JVM 内存中，Nacos 宕机不影响它们。

| 能力 | Nacos 宕机后 |
|------|-------------|
| 现有实例的服务发现 | ✅ 从缓存继续提供 |
| 负载均衡选实例 | ✅ 在缓存的实例列表上轮询 |
| 发现新上线的实例 | ❌ 无法感知 |
| 摘除已下线的实例 | ❌ 缓存中的实例不会被删除 |
| 新服务注册 | ❌ 注册请求发到 Nacos 会失败 |

**"已下线实例不会被摘除"会有什么问题？**

如果某个 mhp-booking 实例崩溃，但 Nacos 宕机导致无法感知，负载均衡仍可能把请求打到已崩溃的实例上。这时需要靠 **Sentinel 熔断**来保底：连续调用失败触发熔断，不再发请求到这个实例，等探测恢复。

**结论：Nacos 短暂宕机（几分钟）对现有调用链路影响很小；长时间宕机主要问题是服务实例列表和注册信息的失准，而不是整个系统崩溃。**

---

## 二、Nacos 配置中心

### 核心概念三级结构

```
Namespace（命名空间）
  └── Group（组）
        └── DataId（配置文件名）
```

| 层级 | 用途 | 示例 |
|------|------|------|
| Namespace | 区分环境（dev/test/prod） | `dev`、`production` |
| Group | 区分项目或业务模块 | `DEFAULT_GROUP`、`MHP_GROUP` |
| DataId | 具体的配置文件 | `mhp-account.yaml` |

### 接入配置中心

```yaml
# application.yaml（Spring Boot 3.x 写法）
spring:
  config:
    import: optional:nacos:mhp-account.yaml  # 从 Nacos 拉取此 DataId 的配置
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yaml
        # namespace: dev          # 指定命名空间（默认 public）
        # group: DEFAULT_GROUP    # 指定组
```

`optional:` 前缀：Nacos 不可达时不报错，用本地配置兜底。

### 优先级

```
Nacos 配置中心 > 本地 application.yaml
```

同一个 key，Nacos 的值会覆盖本地配置。适合把**会变化的配置**（数据库密码、功能开关、限流阈值）放 Nacos，**不会变的**（端口、服务名）留在本地。

---

### 动态刷新：@RefreshScope 的完整原理

#### 触发时机：程序怎么知道配置变了？

Spring Cloud Nacos 在应用启动时，会自动开启一个**长轮询后台线程**：

```
应用启动
  ↓
Nacos 长轮询任务启动
  ↓（持续）
向 Nacos 服务端发送 POST /v1/cs/configs/listener（包含当前所有 DataId 的 MD5）
  Nacos 服务端：挂起这个请求，最多等待 30 秒
  ├── 30 秒内没有变化 → 返回空（下一轮继续长轮询）
  └── 配置被修改    → 立刻响应，返回变化的 DataId 列表
  ↓（配置变化时）
Nacos 客户端重新拉取变化的配置，更新本地缓存
  ↓
Spring Cloud Nacos 发布 RefreshEvent 到 Spring ApplicationContext
  ↓
RefreshEventListener.handle(RefreshEvent)
  ↓
ContextRefresher.refresh() 执行：
  1. 重新读取所有配置源（包括 Nacos），更新 Environment
  2. 找出变化的 key，发布 EnvironmentChangeEvent
  3. 调用 RefreshScope.refreshAll()，把所有 @RefreshScope Bean 标记为"脏"（dirty）
  ↓
下一次有代码访问被标记为"脏"的 Bean
  → Spring 销毁旧实例，用新 Environment 中的值重新创建 Bean，注入新的 @Value
```

#### @RefreshScope 的使用

```java
@RestController
@RefreshScope  // 加这个注解，该 Bean 会在配置变化后延迟重建
public class SomeController {

    @Value("${some.config:default}")
    private String someConfig;

    // 不加 @RefreshScope：配置更新后，someConfig 仍是启动时的旧值
    // 加了 @RefreshScope：配置更新后，下次请求进来，Bean 重建，someConfig 拿新值
}
```

**注意：** `@RefreshScope` 会导致 Bean 的第一次访问有轻微延迟（需要重建），不要加在高频调用的核心 Bean 上（如 Service 层）。

---

### 配置监听 vs @RefreshScope — 两种独立方案

#### 配置监听（addListener）

```java
@PostConstruct
public void init() throws NacosException {
    configService.addListener("mhp-account.yaml", "DEFAULT_GROUP", new Listener() {
        @Override
        public Executor getExecutor() {
            return null; // 用默认线程池
        }

        @Override
        public void receiveConfigInfo(String configInfo) {
            // configInfo 是新配置的完整 YAML/Properties 字符串
            // 你来决定怎么处理：解析、更新某个字段、清缓存...
            log.info("配置已更新：{}", configInfo);
        }
    });
}
```

#### 两者的核心区别

**两者完全独立，各自单独用就能实现动态刷新，不需要配合。**

| | @RefreshScope | configService.addListener |
|---|---|---|
| 谁触发 | Spring Cloud Nacos 自动（长轮询 → RefreshEvent）| Nacos SDK 自动（长轮询 → 你的回调）|
| 你需要写的代码 | 只需在 Bean 上加注解 | 需要手动注册 + 实现回调逻辑 |
| 刷新的是什么 | 重建整个 Bean，所有 `@Value` 字段更新 | 你在回调里写什么就做什么 |
| 适合什么场景 | 配置变化 → `@Value` 字段自动跟着变 | 配置变化 → 需要执行自定义操作（清缓存、重载连接池、通知其他模块）|
| 能否感知具体变化了什么 | 只知道配置变了（Bean 整体重建）| 拿到完整新配置字符串，自己解析 |

#### 什么时候两个一起用？

```java
// 场景：配置变化后，既要刷新 @Value，又要同步清掉依赖这个配置的 Redis 缓存
@RefreshScope  // 处理 @Value 自动刷新
@Service
public class RateLimitService {

    @Value("${rate.limit.qps:100}")
    private int qps;

    // @Value 会自动更新，但 Redis 里的旧缓存不会自动失效
    // 这时可以额外注册 addListener，在回调中手动清 Redis
}
```

#### 本项目用了哪个？

MHP 项目目前**两个都没有用**。原因：当前配置（数据库地址、Redis 地址）是运维级配置，不需要在线热更新；功能开关、限流阈值这类"需要热更新"的配置暂未引入。

如果后续要加热更新（比如让运营可以在线调整某个业务参数），推荐：
- 配置只有 `@Value` 读取 → 只用 `@RefreshScope` 即可
- 配置变化还有副作用（清缓存/通知等）→ 额外加 `addListener`

---

## 三、OpenFeign 远程调用

OpenFeign 是**声明式的 REST 客户端**：写一个接口 + 注解，框架自动生成实现类（基于 JDK 动态代理），在内部发 HTTP 请求到目标服务。

### 工作原理

```
你写的接口 + @FeignClient
       ↓ Spring 启动时（@EnableFeignClients）
JDK 动态代理生成实现类（InvocationHandler）
       ↓ 调用接口方法时
1. 读取方法上的 @GetMapping、@PostMapping 等注解 → 确定 HTTP 方法和路径
2. 读取参数注解（@PathVariable、@RequestBody 等）→ 构建请求
3. 从 Nacos 获取目标服务实例（+ 负载均衡）
4. 发 HTTP 请求（底层用 Apache HttpClient 或 OkHttp）
5. 响应反序列化为返回值类型
```

### 开启 Feign（主启动类）

```java
@SpringBootApplication
@EnableFeignClients(basePackages = "com.mhp.booksystem.feign")
// basePackages：扫描哪个包下的 @FeignClient 接口
// 本项目 FeignClient 在 mhp-common 模块，所以要显式指定包名
public class SocialApplication { ... }
```

### Feign 接口注解详解

```java
@FeignClient(
    name = "mhp-account",   // 目标服务在 Nacos 中注册的名字，用于服务发现
    // url = "http://localhost:8081"  // 如果不走 Nacos，直接指定地址（测试用）
    fallback = AccountFeignClientFallback.class  // 熔断降级类（结合 Sentinel）
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

    // @RequestHeader：放在请求头中（如 token 透传）
    @GetMapping("/some/api")
    Result<Void> someApi(@RequestHeader("token") String token);
}
```

**注解逻辑与 Spring MVC 相反**：
- Spring MVC Controller 上的注解：**接收**请求（我提供什么路径）
- Feign 接口上的注解：**发送**请求（我向谁发什么路径）

### 配置项

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
          mhp-account:                # 仅对 mhp-account 客户端生效（覆盖 default）
            read-timeout: 5000
      httpclient:
        hc5:
          enabled: true              # 启用 Apache HttpClient5（性能更好）
```

**日志级别说明**：

| 级别 | 输出内容 |
|------|---------|
| `NONE` | 不输出（生产环境用） |
| `BASIC` | 请求方法 + URL + 状态码 + 耗时 |
| `HEADERS` | BASIC + 请求头/响应头 |
| `FULL` | HEADERS + 请求体/响应体（调试用） |

---

### 拦截器（RequestInterceptor）详解

#### RequestInterceptor 是 Spring 的还是 OpenFeign 的？

`feign.RequestInterceptor` 是 **OpenFeign 自己的接口**（来自 `feign-core` 库），和 Spring 无关。但 Spring Cloud OpenFeign 的自动配置让它能被 Spring 容器管理的 Bean 自动识别。

#### 为什么加了 @Component 就能自动生效，不需要手动注册？

`FeignClientsConfiguration`（Spring Cloud OpenFeign 的自动配置类）在构建每个 `Feign.Builder` 时，会主动从 Spring 容器中收集所有 `RequestInterceptor` 类型的 Bean：

```java
// FeignClientsConfiguration 内部（简化）
@Autowired(required = false)
private List<RequestInterceptor> requestInterceptors = new ArrayList<>();

@Bean
public Feign.Builder feignBuilder() {
    return Feign.builder()
        .requestInterceptors(requestInterceptors);  // 自动注入所有 RequestInterceptor Bean
}
```

Spring 的依赖注入会自动把容器里所有实现了 `RequestInterceptor` 接口的 Bean 收集进这个 List。你只需要 `@Component`，它就进了容器，OpenFeign 就自动把它加到拦截器链里。

#### RequestTemplate 是什么？apply() 方法能拿到什么？

`feign.RequestTemplate` 是 OpenFeign 内部的 **HTTP 请求草稿对象**。在 `apply()` 被调用时，请求已经被部分构建好了：

```
调用 feignClient.getUser(123L)
  ↓
OpenFeign 解析注解，开始构建 RequestTemplate：
  - HTTP 方法：GET
  - 路径：/internal/user/123（{id} 已替换为 123）
  - 如果有 @RequestBody：body 已经被 Encoder 序列化为 JSON
  - 如果有 @RequestParam：已拼接到 query string
  ↓
依次调用所有 RequestInterceptor.apply(template)  ← 此时你能拦截
  ↓
真正发送 HTTP 请求
```

**在 `apply()` 中你能做什么：**

```java
@Override
public void apply(RequestTemplate template) {
    // 读取当前请求信息
    template.url();                    // 当前路径，如 /internal/user/123
    template.method();                 // 如 GET

    // 添加/覆盖请求头（最常用）
    template.header("token", "xxx");
    template.header("X-Internal", "true");  // 标记内部请求

    // 添加查询参数
    template.query("version", "v1");

    // 覆盖请求体（少用）
    template.body("new body content");
}
```

**本项目中的实际应用：**

```java
@Component
public class FeignTokenInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 场景：Gateway 验证 token 后，下游服务也需要知道是哪个用户
        // 从当前请求的 ThreadLocal 取 token（Sa-Token 写入的），透传给下游服务
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

---

### Encoder / Decoder 自定义

#### 默认的 Encoder / Decoder 做了什么？

```
发送请求时：
  @RequestBody DTO 对象
       ↓ SpringEncoder（默认）
  Jackson 把对象序列化为 JSON 字符串
       ↓
  设置 Content-Type: application/json，写入请求体

收到响应时：
  响应体 JSON 字符串
       ↓ SpringDecoder（默认）
  Jackson 把 JSON 反序列化为 FeignClient 接口方法的返回值类型
       ↓
  返回 Result<UserDTO>
```

#### 可以自定义吗？怎么做？

可以。自定义 Encoder/Decoder 有两种注册方式：

**方式一：全局替换（对所有 FeignClient 生效）**

```java
@Configuration
public class GlobalFeignConfig {

    @Bean
    public Encoder feignEncoder(ObjectMapper objectMapper) {
        // 例：使用自定义 ObjectMapper（比如 snake_case 字段命名）
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
// 先定义一个局部配置类（不加 @Configuration，避免被全局扫描）
public class AccountFeignConfig {
    @Bean
    public Decoder customDecoder() {
        return new MyCustomDecoder();
    }
}

// 在 @FeignClient 上指定
@FeignClient(name = "mhp-account", configuration = AccountFeignConfig.class)
public interface AccountFeignClient { ... }
```

#### 自定义 Decoder 的完整示例

```java
public class MyCustomDecoder implements Decoder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object decode(Response response, Type type) throws IOException {
        // response.body()：响应体字节流
        // type：FeignClient 接口方法的返回类型（如 Result<UserDTO>）
        String body = Util.toString(response.body().asReader(StandardCharsets.UTF_8));

        // 默认行为：直接按 type 反序列化
        return objectMapper.readValue(body, objectMapper.constructType(type));
    }
}
```

#### 本项目有可以用的地方吗？

**有，最实用的场景：自动解包 `Result<T>` 包装层。**

本项目每个内部接口都返回 `Result<T>`，在服务调用方每次都要 `.getData()` 解包，代码很重复：

```java
// 现在的写法（每次都要解包）
Result<UserDTO> result = accountFeignClient.getUser(id);
UserDTO user = result.getData();  // 每次都要这一行

Result<List<UserDTO>> result2 = accountFeignClient.batchGetUsers(ids);
List<UserDTO> users = result2.getData();
```

如果写一个自定义 Decoder，自动把 `Result<T>` 的 `data` 字段提取出来：

```java
public class ResultUnwrappingDecoder implements Decoder {

    private final ObjectMapper objectMapper;

    public ResultUnwrappingDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        String body = Util.toString(response.body().asReader(StandardCharsets.UTF_8));

        // 先解析为 Result 结构，取出 data 字段
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.get("data");

        // 按接口返回类型反序列化 data 部分（如 UserDTO、List<UserDTO>）
        return objectMapper.readValue(
            data != null ? data.toString() : "null",
            objectMapper.constructType(type)
        );
    }
}
```

FeignClient 接口就可以直接声明真实类型：

```java
@FeignClient(name = "mhp-account", configuration = AccountFeignConfig.class)
public interface AccountFeignClient {
    @GetMapping("/internal/user/{id}")
    UserDTO getUser(@PathVariable("id") Long id);  // 直接返回 UserDTO，不是 Result<UserDTO>

    @GetMapping("/internal/user/batch")
    List<UserDTO> batchGetUsers(@RequestParam("ids") List<Long> ids);  // 直接返回 List
}

// 调用方不需要再解包了
UserDTO user = accountFeignClient.getUser(id);
```

> 本项目目前没有这样做，保持的是 `Result<T>` 返回，原因是 `Result` 里的 code/msg 字段在异常处理时会用到（比如判断 code 是否为 200）。是否实现取决于团队对内部接口响应格式的统一约定。

---

### 整合 Sentinel 实现熔断降级

```java
// 1. 定义 Fallback 类（实现 FeignClient 接口）
@Component
public class AccountFeignClientFallback implements AccountFeignClient {

    @Override
    public Result<UserDTO> getUser(Long id) {
        return Result.ok(null);   // mhp-account 挂了或超时，返回兜底数据
    }

    @Override
    public Result<Void> updateMerchantScore(Long id, MerchantScoreUpdateDTO dto) {
        log.error("mhp-account 不可用，更新商家评分失败，merchantId={}", id);
        return Result.ok();
    }
}

// 2. @FeignClient 指定 fallback
@FeignClient(name = "mhp-account", fallback = AccountFeignClientFallback.class)
public interface AccountFeignClient { ... }

// 3. 配置开启（Feign + Sentinel 整合）
spring:
  cloud:
    openfeign:
      sentinel:
        enabled: true
```

### 重试机制

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            retryer: feign.Retryer.Default  # 重试 5 次，初始间隔 100ms，最大 1s
```

**注意**：重试只应用于 GET 请求（幂等）。POST/PUT 等非幂等请求不应开启重试，否则可能产生重复数据。本项目内部接口（`/internal/**`）均为查询或幂等更新，可以配置重试。

---

## 四、OpenFeign 内部实现原理（面试常考）

### 动态代理生成过程

```
@EnableFeignClients 扫描 @FeignClient 接口
       ↓
FeignClientFactoryBean.getObject() 被调用
       ↓
Feign.Builder 构建代理：
  encoder（序列化请求）+ decoder（反序列化响应）+ interceptors（拦截器链）
       ↓ 包装负载均衡
FeignBlockingLoadBalancerClient（内置 Spring Cloud LoadBalancer）
       ↓
JDK Proxy.newProxyInstance() 生成接口的代理对象
       ↓
代理对象注入到 Spring 容器（你 @Autowired 拿到的就是这个代理）
```

### 一次 Feign 调用的完整流程

```
调用 feignClient.getUser(id)
       ↓
代理对象的 InvocationHandler.invoke() 被触发
       ↓
SynchronousMethodHandler.invoke()
  1. 读取方法元数据（注解解析的请求模板）
  2. 解析参数，填充 RequestTemplate（URL、body、query）
  3. 执行所有 RequestInterceptor.apply()（拦截器链，如加 token header）
  4. 调用 FeignBlockingLoadBalancerClient：
       → 从 Nacos 缓存取服务实例列表
       → 负载均衡选一个实例（192.168.1.10:8081）
       → 把 lb://mhp-account 替换为真实地址
  5. 用 HttpClient 发送 HTTP 请求
  6. 检查 HTTP 状态码（4xx/5xx 抛异常）
  7. 用 Decoder 把响应体 JSON 反序列化为返回值类型
  8. 返回给调用方
```

如果调用失败（超时、5xx）且配置了 `fallback`，则调用 fallback 类对应方法返回兜底数据。
