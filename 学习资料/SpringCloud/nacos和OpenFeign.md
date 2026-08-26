# Nacos + OpenFeign

> Nacos 用于作为注册中心和配置中心。

---

## 一、Nacos 注册中心

### 快速接入

Nacos 3.0 强制要求用 MySQL 作为数据库（不再内置），并要求开启鉴权。

```yaml
# application.yaml（各微服务）
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848  # Nacos 地址
```

引入依赖后，微服务启动时自动注册到 Nacos，**不需要任何额外代码**。

### @EnableDiscoveryClient

放在主启动类上，开启服务发现功能（让当前服务能查询其他微服务的实例列表）。

```java
@SpringBootApplication
@EnableDiscoveryClient
public class AccountApplication { ... }
```

> Spring Cloud Commons 规范：无论用 Nacos、Eureka 还是 Consul，此注解都适用。

### DiscoveryClient 底层原理（了解即可，实际用 Feign）

```java
// 底层手动写法，用于理解原理
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

// 调用时像本地方法
BookingDTO booking = bookingFeignClient.getById(id);
```

**两者对比**：

| | DiscoveryClient | OpenFeign |
|---|---|---|
| 代码量 | 多（手动拼 URL）| 少（只写接口）|
| 负载均衡 | 自己实现 | 自动（Spring Cloud LoadBalancer）|
| 使用场景 | 学习底层原理 | 实际开发 |
| 底层 | 相同（都从 Nacos 拿地址再发 HTTP）| 相同 |

### LoadBalancerClient 实现负载均衡

**方式一**：显式调用 `LoadBalancerClient.choose()`

```java
@Autowired
private LoadBalancerClient loadBalancerClient;

ServiceInstance instance = loadBalancerClient.choose("mhp-booking");
String url = "http://" + instance.getHost() + ":" + instance.getPort() + "...";
```

**方式二**：`@LoadBalanced` 注解（更简单）

```java
// 配置类
@Bean
@LoadBalanced  // 让 RestTemplate 自动拦截 http://服务名/... 并替换为真实地址
public RestTemplate restTemplate() {
    return new RestTemplate();
}

// 调用时用服务名代替 IP
restTemplate.getForObject("http://mhp-booking/internal/booking/" + id, BookingDTO.class);
// RestTemplate 内部：截获服务名 → 问 Nacos → 负载均衡选实例 → 替换为真实 IP:Port → 发请求
```

### 实例缓存

微服务启动后，会把 Nacos 返回的实例列表**缓存在本地内存**。即使 Nacos 短暂宕机，已缓存的地址仍然可用，不影响现有调用。Nacos 恢复后，缓存自动刷新。

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
# bootstrap.yaml（或 application.yaml with spring.config.import）
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

`optional:` 前缀：Nacos 不可达时不报错，用本地配置兜底（开发友好）。

### 优先级

```
Nacos 配置中心 > 本地 application.yaml
```

同一个 key，Nacos 的值会覆盖本地配置。适合把**会变化的配置**（数据库密码、功能开关、限流阈值）放 Nacos，**不会变的**（端口、服务名）留在本地。

### 动态刷新 @RefreshScope

```java
@RestController
@RefreshScope  // 加这个注解，当 Nacos 配置变化时，Bean 会被重新创建
public class SomeController {

    @Value("${some.config:default}")
    private String someConfig;

    // 不加 @RefreshScope：Nacos 更新配置后，someConfig 仍是旧值
    // 加了 @RefreshScope：Nacos 更新后，下次请求进来，Bean 重建，someConfig 拿新值
}
```

**注意**：`@RefreshScope` 会导致 Bean 的第一次访问有轻微延迟（需要重建），不要加在高频调用的核心 Bean 上。

### 配置监听（主动感知变化）

```java
// 在 Bean 初始化后主动注册监听器
@PostConstruct
public void init() throws NacosException {
    configService.addListener("mhp-account.yaml", "DEFAULT_GROUP", new Listener() {
        @Override
        public Executor getExecutor() {
            return null; // 用默认线程池
        }

        @Override
        public void receiveConfigInfo(String configInfo) {
            // 配置变化时触发此方法，configInfo 是新配置的完整内容
            log.info("配置已更新：{}", configInfo);
        }
    });
}
```

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

    // @GetMapping：表示发 GET 请求，路径是 /internal/user/{id}
    @GetMapping("/internal/user/{id}")
    Result<UserDTO> getUser(@PathVariable("id") Long id);
    // @PathVariable("id")：路径变量，拼接在 URL 中

    // @PostMapping：发 POST 请求，body 是 JSON
    @PostMapping("/internal/merchant/{id}/score")
    Result<Void> updateMerchantScore(
        @PathVariable("id") Long id,
        @RequestBody MerchantScoreUpdateDTO dto
    );
    // @RequestBody：将 DTO 序列化为 JSON 放在请求体

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

### 拦截器（RequestInterceptor）

**使用场景**：所有 Feign 请求都需要带某个请求头时（如透传 token、内部服务身份标识）。

```java
@Component  // 注册到 Spring 容器，OpenFeign 自动发现所有 RequestInterceptor Bean
public class FeignTokenInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 从当前请求的 ThreadLocal 取 token，透传给下游服务
        // 场景：Gateway 验证 token 后，下游服务也需要知道是哪个用户
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

**原理**：容器中所有 `RequestInterceptor` Bean 会被 OpenFeign 自动扫描并应用到每次请求前，无需手动注册。

### 整合 Sentinel 实现熔断降级

```java
// 1. 定义 Fallback 类（实现 FeignClient 接口）
@Component
public class AccountFeignClientFallback implements AccountFeignClient {

    @Override
    public Result<UserDTO> getUser(Long id) {
        // mhp-account 挂了或超时，返回兜底数据而不是让异常扩散
        return Result.ok(null);   // 或返回默认 UserDTO
    }

    @Override
    public Result<Void> updateMerchantScore(Long id, MerchantScoreUpdateDTO dto) {
        // 评分更新失败，记录日志，后续可通过定时任务补偿
        log.error("mhp-account 不可用，更新商家评分失败，merchantId={}", id);
        return Result.ok();
    }
}

// 2. @FeignClient 指定 fallback
@FeignClient(name = "mhp-account", fallback = AccountFeignClientFallback.class)
public interface AccountFeignClient { ... }

// 3. 配置开启（Feign + Sentinel 整合）
# application.yaml
spring:
  cloud:
    openfeign:
      sentinel:
        enabled: true
```

### 重试机制

```yaml
# 默认不重试，配置后自动重试
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
  2. 执行所有 RequestInterceptor.apply()（拦截器链）
  3. 从 Nacos + LoadBalancer 选一个服务实例
  4. 填充 URL（lb://mhp-account → http://192.168.1.10:8081）
  5. 用 HttpClient 发送 HTTP 请求
  6. 检查 HTTP 状态码（4xx/5xx 抛异常）
  7. 用 Decoder 把响应体 JSON 反序列化为返回值类型
  8. 返回给调用方
```

如果调用失败（超时、5xx）且配置了 `fallback`，则调用 fallback 类对应方法返回兜底数据。
