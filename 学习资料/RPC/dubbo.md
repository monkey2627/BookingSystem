# Dubbo 系统笔记

---

## 一、RPC 整体开发模型

### 从 RPC 到微服务的演化

**RPC（Remote Procedure Call）**：目标是让调用远程服务像调用本地方法一样，开发者不用关心网络细节。

跨进程调用只能走网络，因此需要解决三个问题：**通信方式**（TCP/HTTP）、**协议**（消息格式）、**序列化**（数据如何编码为字节流）。

**SOA**：RPC 架构的演化，解决了两个问题：
1. 调用失败怎么办（需要容错机制）
2. 某个模块访问量大但无法单独扩容

SOA 的解法是将模块独立为进程（服务），可以单独水平扩展。

**微服务**：SOA 的进一步升级。彻底去掉"子系统"概念，所有功能都服务化，每个服务单独部署、单独扩缩容。

### 异构系统的 RPC 调用

不同编程语言的数据类型不兼容，通过 **IDL（接口定义语言）** 定义统一中间类型——如 Protobuf（gRPC/Triple 使用）、Thrift IDL——各语言各自生成对应的代码，实现跨语言 RPC。

---

### Dubbo 的六个核心角色

```
Registry（注册中心，Nacos）
   ↑ 注册                ↑ 订阅/推送变更
Provider ─────────────────────────── Consumer
（服务提供方）      网络调用          （服务消费方）
```

| 角色 | 职责 |
|------|------|
| **Provider** | 暴露服务，启动时向注册中心注册地址和接口信息 |
| **Consumer** | 调用服务，启动时从注册中心订阅所需服务的地址列表 |
| **Registry** | 服务发现中心（本项目用 Nacos），维护 Provider 地址，实时推送变更 |
| **Proxy（代理层）** | Consumer 拿到的不是真实实现，而是框架生成的动态代理；代理内部屏蔽网络通信全过程 |
| **Serialization（序列化）** | 把方法名/参数/返回值编码为字节流（Dubbo 默认 Hessian2，Triple 支持 Protobuf） |
| **Transport（传输层）** | 底层网络通信，Dubbo 默认 Netty；Triple 协议基于 HTTP/2 |

### 为什么 Consumer 调用的是代理对象？

Consumer 引入的是 API 接口（在 mhp-common 定义），没有任何实现类。Dubbo 在启动时通过 `@DubboReference` 扫描，对每个接口用 `Proxy.newProxyInstance()` 生成 JDK 动态代理（或 Javassist 字节码代理）。

代理对象的核心工作：**对 Consumer 屏蔽网络通信的全部细节**。

```
Consumer 调用 rpcMerchantService.getMerchantByUserId(userId)
    ↓ 实际进入代理的 InvocationHandler.invoke()
    ↓ 构建 RpcInvocation（接口名、方法名、参数数组）
    ↓ 序列化 → Netty 发送 → 等待响应
    ↓ 反序列化响应 → 返回结果
```

这就是为什么 Consumer 写的代码和调用本地方法完全一样，而通信效率远高于 OpenFeign：Dubbo 用的是二进制序列化（Hessian2/Protobuf）+ 长连接复用，OpenFeign 是 JSON 文本 + HTTP/1.1 每次建立连接。

### 一次完整 RPC 调用流程

```
Consumer 调用接口方法
    ↓ 动态代理拦截，创建 RpcInvocation
    ↓ 从注册中心维护的地址列表中，经路由过滤 + 负载均衡选出一个 Provider
    ↓ 序列化（方法名 + 参数 → 字节流）
    ↓ Netty 长连接发送请求（TCP / HTTP/2）
    ↓ Provider 接收 → 反序列化 → 反射调用真实实现
    ↓ 结果序列化 → 网络返回
    ↓ Consumer 代理反序列化 → 返回给调用方
```

---

## 二、核心注解与项目配置

### 2.1 Provider 端（服务提供方）

```java
// 1. 在 mhp-common 定义接口（Provider/Consumer 共同依赖，作为 API 契约）
public interface RpcMerchantService {
    MerchantDTO getMerchantByUserId(Long userId);
    void updateMerchantScore(Long merchantId, MerchantScoreUpdateDTO dto);
}

// 2. 在 mhp-account 实现接口，@DubboService 暴露为 Dubbo 服务
@DubboService(version = "1.0.0")      // Dubbo 注解，负责注册到 Nacos 并暴露服务
@Service                               // 同时加 Spring @Service 保证兼容性
@RequiredArgsConstructor
public class RpcMerchantServiceImpl implements RpcMerchantService {
    private final MerchantService merchantService;

    @Override
    public MerchantDTO getMerchantByUserId(Long userId) {
        // 查 DB，转换为 DTO 返回（DTO 必须实现 Serializable，Hessian2 要求）
    }
}
```

Spring Boot 扫描到 `@DubboService` 修饰的类时，会创建其 Bean 并同时将其发布为 Dubbo 服务，注册到 Nacos。

### 2.2 Consumer 端（服务消费方）

```java
@Service
@RequiredArgsConstructor
public class BookingServiceImpl {

    private final AccountFeignClient accountFeignClient;   // 普通 Feign（final，构造器注入）

    @DubboReference(version = "1.0.0")                    // Dubbo 代理（非 final，Dubbo 自己注入）
    private RpcMerchantService rpcMerchantService;

    public void someMethod() {
        // 直接调用，和本地方法完全一样
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
    }
}
```

**为什么 `@DubboReference` 字段必须是非 final？**

Dubbo 使用自己的字段注入机制（扫描 `@DubboReference` 注解后通过反射赋值），不通过 Spring 构造器注入。`final` 字段只能在构造器中赋值，Lombok 的 `@RequiredArgsConstructor` 不会包含 Dubbo 代理字段，所以必须声明为普通字段。

### 2.3 启动类

```java
@SpringBootApplication
@EnableFeignClients(basePackages = "com.mhp.booksystem.feign")
@EnableDubbo(scanBasePackages = "com.mhp.booksystem")  // 扫描 @DubboService 并发布
public class AccountApplication { ... }
```

`@EnableDubbo` 等价于 `@DubboComponentScan`，默认扫描启动类所在包及其子包，也可在 yaml 中配置：

```yaml
dubbo:
  scan:
    base-packages: com.mhp.booksystem
```

### 2.4 application.yaml 配置

```yaml
dubbo:
  application:
    name: mhp-account           # 服务名，注册到 Nacos 时的标识（Dubbo 3 应用级注册用）
    qos-enable: false           # 关闭 QoS 端口（默认 22222，多服务同机容易冲突）
  protocol:
    name: tri                   # Triple 协议（HTTP/2，Dubbo 3 推荐）
    port: -1                    # 随机端口，避免与 Spring Boot HTTP 8081 冲突
  registry:
    address: nacos://127.0.0.1:8848   # 复用已有 Nacos，不引入新中间件
```

**QoS 端口冲突**：Dubbo 的 QoS（Quality of Service）管理端口默认 22222，多个 Dubbo 服务在同一台机器上运行时会冲突，生产或本地多服务启动时务必关闭或分配不同端口。

### 2.5 直连模式（等价于 OpenFeign 的意义）

```java
// 直接指定 Provider 的 IP:Port，完全绕过注册中心
@DubboReference(url = "tri://192.168.1.10:20880", version = "1.0.0")
private RpcMerchantService rpcMerchantService;
```

**直连模式 = OpenFeign 硬编码 URL 的等价形式：**

| | Dubbo 直连 | OpenFeign 直连 |
|--|-----------|--------------|
| 写法 | `@DubboReference(url="tri://ip:port")` | `@FeignClient(url="http://ip:port")` |
| 协议 | HTTP/2 + Hessian2（二进制） | HTTP/1.1 + JSON（文本） |
| 共同缺陷 | 地址硬编码，无服务发现，无法动态扩缩容，无法感知故障 |

直连模式仅用于**本地开发调试**（无需启动 Nacos 即可联调两个服务），不具备生产能力。

**注意**：OpenFeign + Nacos 同样实现了服务发现——Spring Cloud LoadBalancer 也订阅 Nacos、本地缓存地址列表、感知节点上下线，这一层两者能力对等。注册中心不是 Dubbo 的差异化优势，Dubbo 真正超越 OpenFeign 的地方在于：

1. **协议效率**：HTTP/2 + 二进制序列化 vs HTTP/1.1 + JSON，体积更小、CPU 开销低、多路复用避免队头阻塞
2. **服务治理深度**：内置 Router（条件/标签/灰度路由）、多种集群容错策略、Filter 链（限流/监控）；OpenFeign 侧只有 LoadBalancer
3. **接口契约强度**：Java 接口共同依赖，参数类型/方法签名不匹配在编译期报错；OpenFeign 的 HTTP URL + 注解只能在运行时发现错误

---

## 三、注册中心原理（以 Nacos 为例）

### 3.1 注册中心解决的两个核心问题

1. **服务发现**：Consumer 无需知道 Provider 的具体 IP，只需知道服务名，框架自动解析
2. **动态感知**：Provider 集群扩缩容、故障，Consumer 无需重启，自动更新可用列表

### 3.2 服务注册的数据结构（Dubbo 2 vs Dubbo 3）

Provider 启动时向 Nacos 注册的内容，Dubbo 2 和 Dubbo 3 有本质区别：

#### Dubbo 2：接口级注册

每个接口在 Nacos 单独注册一条数据，URL 参数携带完整元信息：

```
Nacos 中的服务名：
  providers:com.mhp.booksystem.rpc.RpcMerchantService:1.0.0:

实例 URL（URL 编码存储）：
  tri://192.168.1.10:20880/com.mhp.booksystem.rpc.RpcMerchantService
    ?application=mhp-account
    &version=1.0.0
    &methods=getMerchantByUserId,updateMerchantScore
    &timeout=3000
    &serialization=hessian2
    &side=provider
    ...
```

**问题**：一个应用暴露 100 个接口 → Nacos 存 100 条记录。大规模场景（数百个服务 × 数百个接口）会导致 Nacos 数据量膨胀，每次节点变更触发大量推送（推送风暴），严重影响注册中心稳定性。

#### Dubbo 3：应用级注册（默认，对齐 Spring Cloud 服务模型）

以应用实例为单位注册，接口信息从 Nacos 中分离：

```
Nacos 中的服务名：mhp-account
实例信息：
  IP: 192.168.1.10, Port: 20880, Protocol: tri
  Metadata: {
    "dubbo.metadata.storage-type": "remote",
    "dubbo.protocols.tri.port": "20880",
    "revision": "abc123"   ← 接口元数据的版本摘要
  }

接口/方法详情 → 存入独立的元数据中心（可以是 Nacos、Redis、ZooKeeper）
```

100 个接口的应用，Nacos 中只有 **1 条**实例记录。Consumer 的发现流程变为两步：

```
第一步：Nacos → 拿到应用实例的 IP:Port（轻量，地址信息）
第二步：元数据中心 → 查该实例暴露了哪些接口、方法签名是什么
第三步：Dubbo 框架在 Consumer 本地建立接口到 Invoker 的映射，完成调用
```

**Dubbo 2 vs Dubbo 3 注册模型对比：**

| 维度 | Dubbo 2（接口级） | Dubbo 3（应用级） |
|------|----------------|----------------|
| Nacos 数据量 | 接口数 × 实例数 条记录 | 实例数 条记录（减少 N 倍，N=接口数）|
| 元数据位置 | 全部在 Nacos URL 参数里 | 地址在 Nacos，接口详情在元数据中心 |
| 推送粒度 | 接口级变更推送（细，量大） | 实例级推送（粗，量小）|
| 与 Spring Cloud 兼容 | 不兼容（Nacos 中数据格式不同）| 兼容（同一 Nacos 服务名，同一实例视图）|

### 3.3 Consumer 端服务发现的内部机制

**Consumer 不是每次调用都去 Nacos 查询，而是订阅一次、本地维护实时列表。**

```
Consumer 启动
    ↓
向 Nacos 订阅目标服务（createSubscribeUrl）
  → 建立长连接 / 长轮询监听通道
    ↓
Nacos 立即返回初始实例列表（全量推送）
    ↓
Dubbo 为每个实例 URL 创建 Invoker
  每个 Invoker = 封装了一条 Netty 长连接（提前建立，复用）
    ↓
RegistryDirectory 持有 {URL → Invoker} 的映射表
  = 当前所有可用 Provider 的连接池
    ↓
Nacos 实时推送增量变更
  ├── Provider A 上线 → RegistryDirectory 新增 Invoker A + 建立新 Netty 连接
  └── Provider B 下线 → RegistryDirectory 移除 Invoker B + 关闭对应连接
```

**RegistryDirectory 是 Consumer 侧的核心组件**：
- 负责订阅并监听 Nacos 的服务实例变更事件
- 维护 `URL → Invoker` 映射，增量更新（不重建全量连接，只修改差异部分）
- 对外暴露 `list(invocation)` 方法，供负载均衡层获取当前可用 Invoker 集合

**本地磁盘缓存（注册中心宕机兜底）**：

Dubbo 每次收到地址列表都会同步写入磁盘文件：
```
~/.dubbo/dubbo-registry-mhp-account-127.0.0.1:8848.cache
```

Nacos 宕机时：
- **已有连接不受影响**：Consumer 与 Provider 之间是直连的 Netty 长连接，不经过 Nacos
- **新启动的 Consumer**：从磁盘缓存文件读取上次保存的地址列表，仍可正常调用

这就是"注册中心短暂宕机，服务调用不受影响"的底层原理。

### 3.4 Nacos 心跳机制与故障摘除

```
Provider 正常运行时：
  每隔 5 秒向 Nacos 发一次心跳（Lease Renewal）

Provider 异常宕机（进程 crash，无法主动注销）：
  Nacos 超过 15 秒未收到心跳 → 标记实例为 unhealthy（保留记录，不推送下线）
  Nacos 超过 30 秒未收到心跳 → 从实例列表摘除 → 推送变更给所有订阅的 Consumer
  Consumer 收到推送           → RegistryDirectory 移除对应 Invoker，后续不再打过去
```

**感知延迟是理解集群容错存在意义的关键：**

Provider 宕机到 Consumer 感知，最长有 **30 秒延迟**。这 30 秒内 Consumer 仍会向已宕机的 Provider 发请求，收到连接拒绝或超时异常。`failover`（自动重试切换）就是用来填补这个感知窗口期的——检测到某个 Invoker 失败，自动从 RegistryDirectory 选另一个 Invoker 重试。

**优雅下线（正常停机不需等 30 秒）：**

```
Provider 收到停机信号（SIGTERM / kill）
    ↓
Dubbo ShutdownHook 自动触发（JVM 关闭钩子）
    ↓
① 停止接受新请求（关闭监听端口）
② 等待当前正在处理的请求完成（等待时间 = shutdownTimeout，默认 10s）
③ 向 Nacos 主动发注销请求（deregister）
    ↓
Nacos 立即从实例列表移除 → 推送给所有 Consumer
    ↓
Consumer RegistryDirectory 立即移除该 Invoker → 后续请求零感知切换
```

优雅下线 + 注册中心主动推送 = 正常停机时 Consumer 几乎感知不到节点下线。

### 3.5 本项目的注册中心复用策略

Dubbo 注册中心直接复用 Spring Cloud 已有的 Nacos（`nacos://127.0.0.1:8848`），不引入新中间件。Nacos 上同时存在两类注册信息：

| 类型 | 服务名 | 协议 | 用于 |
|------|--------|------|------|
| Spring Cloud 服务注册 | `mhp-account` | HTTP | OpenFeign 负载均衡 |
| Dubbo 服务注册 | `mhp-account` | tri:// | Dubbo RPC 服务发现 |

Nacos 通过实例 metadata 中的 `dubbo.protocols` 字段区分两者。OpenFeign 的 Ribbon/LoadBalancer 只关注 HTTP 实例，Dubbo 的 RegistryDirectory 只处理带 tri:// 协议的实例，两套机制互不干扰。

---

## 四、Dubbo 内部调用层次模型

理解这一层，是理解负载均衡、集群容错、路由、Filter 如何协作的关键。表面上看是一次普通的方法调用，实际上经过了七层处理：

```
Consumer 代码：rpcMerchantService.getMerchantByUserId(userId)
          ↓
[Proxy 层] JDK 动态代理拦截
  构建 RpcInvocation（接口名、方法名、参数数组）
          ↓
[Cluster 层] 集群容错（FailoverCluster / FailfastCluster...）
  决定失败时如何处理（重试/快速失败/忽略）
  向 Directory 请求当前可用 Invoker 列表
          ↓
[Directory 层] RegistryDirectory
  返回当前所有可用 Invoker（从订阅 Nacos 的实时列表中维护）
          ↓
[Router 层] 路由过滤链（多个 Router 串联）
  TagRouter：按标签隔离（灰度，v2 流量只打 v2 标签节点）
  ConditionRouter：按条件路由（region=bj 只打 beijing 机房）
  ScriptRouter：Groovy 脚本自定义路由
  → 输出：满足条件的 Invoker 子集
          ↓
[LoadBalance 层] 负载均衡
  从子集中按策略选出一个 Invoker
  RandomLoadBalance / RoundRobinLoadBalance / LeastActiveLoadBalance...
          ↓
[Filter 链] AOP 切面（在选定 Invoker 上，调用前后各执行一遍）
  Consumer 侧 Filter：
    ConsumerContextFilter → 传递隐式参数（traceId、来源标记）
    ActiveLimitFilter → 最大并发数限制（Consumer 侧熔断雏形）
    MonitorFilter → 上报调用统计
  Provider 侧 Filter：
    ExecuteLimitFilter → 服务端线程数上限
    TpsLimitFilter → TPS 限流
    AccessLogFilter → 访问日志
          ↓
[Protocol/Invoker 层] DubboInvoker（实际协议执行）
  序列化请求体（Hessian2 / Protobuf）
  通过已建立的 Netty Channel 发送二进制帧
  等待响应（同步阻塞 / 异步 CompletableFuture）
  反序列化响应 → 返回
          ↓
Netty Channel（TCP / HTTP/2 长连接）→ Provider
```

**各层职责一览：**

| 层 | 代表类 | 职责 | 可替换（SPI）|
|----|-------|------|-----------|
| Proxy | `ProxyFactory` | 生成动态代理，透明化远程调用 | ✓ |
| Cluster | `Cluster` | 容错策略（failover/failfast/...）| ✓ |
| Directory | `RegistryDirectory` | 维护可用 Invoker 列表，订阅注册中心 | ✓ |
| Router | `Router` 链 | 路由过滤（全量 → 子集）| ✓ |
| LoadBalance | `LoadBalance` | 从子集选一个（选择策略）| ✓ |
| Filter | `Filter` 链 | AOP 横切（限流/日志/监控）| ✓ |
| Protocol | `Invoker` | 实际序列化 + 网络发送 | ✓ |

**这七层职责单一，全部通过 SPI 可替换——这就是 Dubbo 高扩展性的架构基础。**

Router 做**过滤**（从全量到子集），LoadBalance 做**选择**（从子集到一个），Cluster 做**容错**（选定的那个失败后怎么办），三者职责不重叠，共同决定"请求打到哪里"。

### 同步与异步的底层实现

Dubbo 底层**总是异步的**（Netty NIO 天然异步），同步调用只是在 CompletableFuture 上阻塞等待响应：

```java
// 默认同步调用：框架内部 CompletableFuture.get(timeout)，阻塞当前线程等响应
MerchantDTO result = rpcMerchantService.getMerchantByUserId(userId);

// 显式异步调用（Dubbo 3）：发出请求立即返回 Future，不阻塞当前线程
CompletableFuture<MerchantDTO> future = RpcContext.getClientAttachment()
    .asyncCall(() -> rpcMerchantService.getMerchantByUserId(userId));
future.thenAccept(merchant -> { /* 异步回调 */ });
```

异步调用的意义：同一个线程可以同时发出多个 RPC 请求，不用等第一个回来才发第二个，适合需要并行查询多个服务的场景。

---

## 五、负载均衡策略

Consumer 通过 Router 过滤后得到 Invoker 子集，再由 LoadBalance 从中选出一个发请求。

| 策略 | 算法 | 适用场景 |
|------|------|---------|
| `random`（默认） | 按权重随机 | 通用，机器性能相近时最均衡 |
| `roundrobin` | 加权轮询（Smooth Weighted RR，避免同一时刻流量集中）| 通用，各机权重不同时 |
| `leastactive` | 最少活跃数（正在处理的请求最少的 Provider 优先）| 机器性能差异大，让强机多处理 |
| `consistenthash` | 一致性哈希（相同参数总是打到同一台）| 需要会话粘性，如本地缓存场景 |
| `shortestresponse` | 最短响应时间优先 | 对延迟极敏感 |

```java
@DubboReference(version = "1.0.0", loadbalance = "leastactive")
private RpcMerchantService rpcMerchantService;
```

---

## 六、集群容错策略

LoadBalance 选出的 Invoker 调用失败时，Cluster 层决定如何处理：

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| `failover`（默认） | 失败自动切换到另一台重试（默认重试 2 次） | 读操作（查询），幂等接口 |
| `failfast` | 失败立即报错，不重试 | 写操作（创建、支付），非幂等接口 |
| `failsafe` | 失败忽略，不抛异常，返回空结果 | 日志上报、审计等次要操作 |
| `failback` | 失败后异步定期重试，框架后台补偿 | 最终一致性场景（消息补偿）|
| `forking` | 并发调用多台，只要有一台成功就返回 | 对延迟极敏感，可接受资源浪费 |
| `broadcast` | 广播所有 Provider，有一台失败即整体失败 | 刷新所有节点的本地缓存 |

```java
// 写操作用 failfast，防止重试导致重复写入
@DubboReference(version = "1.0.0", cluster = "failfast")
private RpcMerchantService rpcMerchantService;
```

**面试要点**：`getMerchantByUserId` 是幂等查询 → `failover`（默认）；`updateMerchantScore` 是写操作 → `failfast` 或 `retries=0`。

---

## 七、序列化

常见序列化方式对比：

| 方式 | 格式 | 性能 | 跨语言 | Dubbo 支持 |
|------|------|------|--------|-----------|
| Hessian2 | 二进制 | 高 | 有限 | 默认 |
| Protobuf | 二进制 | 极高 | 完整 | Triple 协议支持 |
| JSON（Jackson）| 文本 | 中 | 完整 | 支持 |
| Kryo | 二进制 | 极高 | Java 专属 | 需引入 `dubbo-serialization-kryo` |
| Java 原生 | 二进制 | 低 | Java 专属 | 支持（不推荐）|

**Hessian2 的要求**：传输的 DTO 类必须实现 `java.io.Serializable` 并声明 `serialVersionUID`，否则反序列化会失败。

**Kryo 的使用**（如需极致性能）：
1. 引入 `dubbo-serialization-kryo` 依赖
2. 配置 `serialization: kryo`
3. Consumer 直连时在 URL 后追加 `?serialization=kryo`

---

## 八、SPI 扩展机制

### 什么是 SPI？

SPI（Service Provider Interface）是接口与实现解耦的标准机制。Dubbo 用自己增强版的 SPI 驱动整个框架，所有核心组件（协议、序列化、负载均衡、集群容错、注册中心…）都是 SPI 扩展点，可以用一行配置替换。

### Dubbo SPI vs Java SPI

| 对比项 | Java SPI | Dubbo SPI |
|--------|---------|-----------|
| 加载方式 | 全部加载（不管用不用，扫描 META-INF/services/）| 按名字懒加载（只加载用到的实现）|
| 扩展点 | 不支持 IoC/AOP | 支持 IoC 注入、AOP Wrapper 包装 |
| 自适应扩展 | 不支持 | `@Adaptive`：根据 URL 参数动态选择实现（运行时决定用哪个）|
| Activate 机制 | 不支持 | `@Activate`：根据条件自动激活（如只有在 Consumer 侧才加载某 Filter）|

### Dubbo SPI 查找路径

```
META-INF/dubbo/             ← 自定义扩展首选放这里
META-INF/dubbo/internal/    ← Dubbo 内部扩展
META-INF/services/          ← 兼容 Java SPI
```

### 如何自定义扩展

```java
// 1. 实现扩展接口
public class MyLoadBalance implements LoadBalance {
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        return invokers.get(0);   // 永远选第一个（示例）
    }
}

// 2. 在 META-INF/dubbo/ 下创建文件，文件名 = 扩展接口的全限定名
// 文件：META-INF/dubbo/org.apache.dubbo.rpc.cluster.LoadBalance
myLB=com.example.MyLoadBalance

// 3. 使用扩展
@DubboReference(loadbalance = "myLB")
```

**面试答法**：Dubbo SPI 让框架每个核心点都是可替换的插件：序列化、负载均衡、集群容错、协议、注册中心……这是 Dubbo 能对接如此多生态的核心原因。项目中没有自定义 SPI，但理解它让我能回答"为什么 Dubbo 能在不改源码的情况下支持那么多扩展"这类问题。

---

## 九、超时、重试、版本、分组

### 9.1 超时（timeout）

```java
// Provider 侧：允许方法执行的最长时间，超过则返回超时异常给 Consumer
@DubboService(version = "1.0.0", timeout = 3000)   // 单位 ms

// Consumer 侧：等待响应的最长时间（Consumer 优先级高于 Provider）
@DubboReference(version = "1.0.0", timeout = 2000)
```

**优先级**：Consumer 方法级 > Consumer 接口级 > Provider 方法级 > Provider 接口级 > 全局配置。

### 9.2 重试（retries）

```java
@DubboReference(version = "1.0.0", retries = 2)   // 失败后最多重试 2 次（不含第一次）
```

**核心原则**：`retries=0` 等价于 `failfast`。**写操作必须设 `retries=0`**，否则 failover 的自动重试可能导致数据重复写入。

### 9.3 版本（version）

```java
// 同一接口的多版本并存（灰度发布）
@DubboService(version = "1.0.0")
public class RpcMerchantServiceImplV1 implements RpcMerchantService { ... }

@DubboService(version = "2.0.0")
public class RpcMerchantServiceImplV2 implements RpcMerchantService { ... }

// Consumer 指定版本
@DubboReference(version = "1.0.0")   // 只调旧版
@DubboReference(version = "*")       // 随机调任意版本（灰度）
```

**用途**：接口不兼容升级时的平滑过渡；灰度发布（部分 Consumer 切到新版测试）。

### 9.4 分组（group）

```java
// 同一接口的不同业务实现，按 group 隔离
@DubboService(group = "vip")
public class VipMerchantServiceImpl implements RpcMerchantService { ... }

@DubboService(group = "normal")
public class NormalMerchantServiceImpl implements RpcMerchantService { ... }

@DubboReference(group = "vip")   // 只调用 vip 分组
```

**用途**：同一接口按业务场景路由到不同实现，如 VIP 用户走专属服务实例。

---

## 十、Dubbo RPC vs OpenFeign HTTP

| 对比维度 | Dubbo3 Triple（RPC） | OpenFeign（HTTP REST） |
|---------|---------------------|----------------------|
| **协议** | HTTP/2（Triple）/ 私有 TCP（Dubbo 老协议）| HTTP/1.1 |
| **序列化** | Hessian2 / Protobuf（二进制，紧凑）| JSON（文本，可读）|
| **连接模型** | 长连接复用（Netty Channel，提前建立）| 短连接或 HTTP Keep-Alive |
| **性能** | 更高（二进制 + 多路复用 + Header 压缩）| 相对低（文本 + 连接开销）|
| **接口契约** | 强类型 Java 接口，编译期检查参数/返回类型 | HTTP URL + JSON，运行时发现错误 |
| **错误传播** | 异常直接序列化透传给 Consumer | 需解包 HTTP 响应体，手动判断 code |
| **适用场景** | 服务间强依赖调用（失败 = 业务失败）| 展示数据补充、跨语言、外部接口 |
| **跨语言** | Triple 与 gRPC 互通（Protobuf）| 天然跨语言（任何语言都能发 HTTP）|

### 本项目的分工原则

**选 Dubbo（强业务依赖）的 3 个调用：**

| 调用 | 调用方 | 选 Dubbo 的理由 |
|------|--------|----------------|
| `getMerchantByUserId` | mhp-booking | 每次写操作的商家身份前置校验，失败则整个操作终止，不可降级 |
| `updateMerchantScore` | mhp-social | 唯一的跨服务写操作，强一致性要求，接口契约必须严格 |
| `getBookingById` | mhp-social | 评价/投诉的业务前置条件（必须存在且已完成），失败则拒绝业务 |

**保留 Feign（弱业务依赖）的 4 个调用：**

| 调用 | 调用方 | 保留 Feign 的理由 |
|------|--------|-----------------|
| `batchGetUsers` | booking / social | 展示数据（头像昵称），失败可降级为占位符 |
| `batchGetMerchants` | booking / social | 展示数据，未来可被 Canal CDC 本地缓存取代 |
| `getMerchant` | social 投诉流程 | 辅助信息查询，非核心校验，失败可降级 |
| `getUser` | social 消息流程 | 存在性检查，可接受最终一致性 |

**面试标准答法**：

> 我选择 Dubbo 的标准是：**这个调用失败后，整个业务操作是否必须终止，且没有降级空间？** 如果是——比如商家发布档期前的身份验证，失败了就不能继续，这是强前置条件——用 Dubbo，因为 Triple 协议基于 HTTP/2，多路复用性能更好，强类型接口在编译期就能发现参数错误，异常也能直接透传无需解包。如果不是——比如列表页展示用户昵称，失败了可以显示"用户已注销"——继续用 OpenFeign，失败不影响主业务，且这类读操作未来可以用 Canal CDC 同步到本地缓存，彻底消除跨服务调用。

---

## 十一、传输协议原理与 Triple

### 11.1 两者都在 Provider 端执行代码（常见误解澄清）

OpenFeign 和 Dubbo 干的事情在本质上完全相同：

```
① 把"调什么方法、传什么参数"打包
② 通过网络发给 Provider
③ Provider 执行真实代码，把结果打包
④ 结果通过网络返回给 Consumer
```

Consumer 拿到的永远是**运行好的结果**，不是代码本身。

反射（`method.invoke()`）发生在 **Provider 端**：Provider 从二进制包里解出方法名后，用反射找到真实实现类并调用。OpenFeign 的 Provider 端由 Spring MVC 路由到 `@GetMapping` 方法，机制不同但位置一样——都在 Provider 跑，结果再传回 Consumer。

**OpenFeign 算 RPC 吗？**

广义上算（远程调用、对调用方透明）。但业界习惯上"RPC 框架"特指使用二进制协议的（Dubbo、gRPC、Thrift），OpenFeign 通常被叫做"声明式 HTTP 客户端"。这是分类习惯，不影响本质。

---

### 11.2 三种传输方式的底层格式

理解 Triple 之前，先把三种格式并排看清楚。

#### 老 Dubbo 协议（`dubbo://`）：纯 TCP，完全没有 HTTP

直接在 TCP 上发自定义格式的二进制数据，没有 GET/POST，没有 URL 路径，Nginx 完全不认识：

```
TCP 字节流（Dubbo 自定义帧格式）：
┌──────────┬──────────┬──────────┬────────────────────────────────────┐
│ 魔数     │ 标志位   │ 请求 ID  │  正文（接口名+方法名+参数，二进制） │
│ 0xdabb   │ 1字节    │ 8字节    │  N字节（Hessian2 序列化）           │
└──────────┴──────────┴──────────┴────────────────────────────────────┘
```

双方事先约定好字节位置的含义，收到后直接按偏移量解析，和 HTTP 毫无关系。

#### HTTP/1.1（OpenFeign）：纯文本，人眼可读

```
GET /api/user/123 HTTP/1.1
Host: mhp-account
Content-Type: application/json

{"id": 123}
```

逐行文本解析，方法名体现在 HTTP Method（GET/POST），参数在 URL path 或 JSON body 里，结果是 JSON 文本。**同一 TCP 连接上请求必须串行**：发完一个等响应，再发下一个。

#### HTTP/2（Triple 使用）：二进制帧 + 多路复用

HTTP/2 彻底重新设计了底层格式，不再是文本，改成二进制帧：

```
HTTP/2 帧（所有数据都拆成这种结构传输）：
┌──────────────┬──────────┬──────────┬─────────────────────┐
│ 帧长度  3字节│ 帧类型   │ Stream号 │  有效载荷（二进制）  │
└──────────────┴──────────┴──────────┴─────────────────────┘
```

**Stream 是 HTTP/2 的核心概念**：一个 TCP 连接上可以同时存在多个 Stream，每个 Stream 是一次独立的请求/响应，Stream 之间完全并行互不阻塞：

```
HTTP/1.1（串行，同一连接只能一个接一个）：
  ──请求1──▶ 等 ──▶ 响应1 ──请求2──▶ 等 ──▶ 响应2──

HTTP/2（多路复用，同一连接并发多个 Stream）：
  ── Stream1 请求 ──▶
  ── Stream2 请求 ──▶  ◀── Stream1 响应 ──
  ── Stream3 请求 ──▶  ◀── Stream3 响应 ──
                        ◀── Stream2 响应 ──
```

HTTP/2 虽然是二进制格式，但**保留了 HTTP 的语义概念**（method、path、headers），只是用二进制帧而非文本来表达这些信息。所以 Nginx、Envoy 这类基础设施能识别和转发它——它们认识"这是 HTTP/2"，即使看不懂 body 里的二进制内容。

---

### 11.3 Triple 实际发什么

Triple 遵循 gRPC 协议规范，借 HTTP/2 的壳传 Dubbo 的二进制数据：

```
HTTP/2 HEADERS 帧（元信息）：
  :method = POST       ← 永远是 POST（因为总有请求体，没有 GET 的概念）
  :path = /com.mhp.booksystem.rpc.RpcMerchantService/getMerchantByUserId
                       ← 路径格式是 /接口全限定名/方法名，不是 REST 风格
  content-type = application/grpc+proto

HTTP/2 DATA 帧（实际数据）：
  ┌─────────────── gRPC 帧头（固定 5 字节）──────────────┐
  │ 第1字节：压缩标志（0=不压缩，1=gzip）                │
  │ 第2~5字节：后续消息体字节数（4字节大端 int）          │
  └──────────────────────────────────────────────────────┘
  后续字节：Hessian2 / Protobuf 序列化的参数值（二进制）

HTTP/2 Trailers 帧（响应结束，携带 END_STREAM 标志）：
  grpc-status: 0      ← 0=OK，1=CANCELLED，2=UNKNOWN，13=INTERNAL
  grpc-message: ""    ← 出错时的错误描述文字
```

Provider 收到后，从 path 里解出接口名和方法名，剥掉 DATA 帧里的 gRPC 5字节前缀，反序列化剩余字节拿到参数，然后用反射调用真实实现，把结果序列化后写回 DATA + Trailers。

---

### 11.4 为什么 Triple 选 HTTP/2，而不是继续用私有 TCP

不是为了 GET/POST 这些语义，核心原因只有一个：

**HTTP/2 是 Nginx、Envoy、API Gateway 等基础设施都认识的标准协议。**

老 `dubbo://` 协议是私有 TCP，Nginx 不认识，没法转发，也没法做 TLS 终止，在 Service Mesh（如 Istio）环境里更是寸步难行。换成 HTTP/2 之后，整个云原生基础设施都能无缝处理 Dubbo 的流量。

同时，gRPC 用的也是 HTTP/2 + Protobuf，Triple 遵循同一规范，所以 Dubbo 服务可以直接被 gRPC 客户端（Go/Python/Node.js 等）调用，实现跨语言互通。

| | 老 Dubbo（dubbo://）| Triple（HTTP/2）|
|--|------------------|----------------|
| 底层传输 | 裸 TCP | HTTP/2 |
| 格式 | 自定义二进制帧 | HTTP/2 二进制帧 |
| 能过 Nginx | ✗ | ✓ |
| 与 gRPC 互通 | ✗ | ✓ |
| 多路复用 | 自己实现 | HTTP/2 原生支持 |

`port: -1` 的原理：Dubbo 启动时随机找一个可用端口绑定，然后把**实际端口**注册到 Nacos。Consumer 从 Nacos 拿到的地址里包含真实端口，无需手动配置，避免与 Spring Boot HTTP 端口冲突。

---

### 11.5 Triple 的两种工作模式

Triple 根据是否使用 Protobuf IDL 分两种模式，HTTP/2 帧结构完全相同，只是 DATA 帧里的序列化格式不同：

#### 模式一：Protobuf IDL 模式（与 gRPC 完全互通）

先写 `.proto` 文件定义服务契约，再用 protoc + Dubbo 插件生成 Java Stub：

```proto
service RpcMerchantService {
    rpc GetMerchantByUserId (GetMerchantRequest) returns (MerchantResponse);
}
message GetMerchantRequest { int64 userId = 1; }
message MerchantResponse   { int64 id = 1; string nickname = 2; }
```

框架用 Protobuf 序列化，`content-type: application/grpc+proto`，DATA 帧里是标准 Protobuf 二进制。

- **优点**：与任何 gRPC 客户端（Go / Python / Node.js）无缝互通，跨语言能力最强
- **缺点**：需要维护 `.proto` 文件；改接口须同步改 IDL 并重新生成代码

#### 模式二：Java Interface 模式（项目实际使用，迁移成本低）

直接用 Java 接口定义，不写 `.proto`：

```java
public interface RpcMerchantService {
    MerchantDTO getMerchantByUserId(Long userId);
}
```

Dubbo 框架内部把调用参数包装进 `TripleRequestWrapper`（本身是一条 Protobuf 消息），再套上 gRPC 5字节前缀放入 DATA 帧：

```
HEADERS 帧（接口名/方法名在这里）：
  :path = /com.mhp.booksystem.rpc.RpcMerchantService/getMerchantByUserId

DATA 帧有效载荷：
  5字节 gRPC 前缀 + TripleRequestWrapper（Protobuf 编码）
    └── args:          [bytes(Hessian2(userId=123))]  ← 每个参数序列化后的字节
    └── argTypes:      ["java.lang.Long"]             ← 参数类型名
    └── serializeType: "hessian2"                     ← 序列化方式
```

接口名和方法名来自 HEADERS 帧的 `:path`，不在 TripleRequestWrapper 里。

- **优点**：和写普通 Java 接口一样，无 IDL，从老 Dubbo 迁移无需改接口代码
- **缺点**：`TripleRequestWrapper` 是 Dubbo 私有格式，纯 gRPC 客户端无法直接调用

**本项目用 Java Interface 模式。** 两种模式在 HTTP/2 帧结构上完全相同（HEADERS + DATA + Trailers），对 Nginx/Envoy 等基础设施的穿透能力没有区别。

---

### 11.6 Triple 的四种调用模型

HTTP/2 的双向流能力让 Triple 支持四种调用模型，由不同数量的 DATA 帧组合表达：

| 模型 | 请求侧 | 响应侧 | 典型场景 |
|------|--------|--------|---------|
| **Unary（一元）** | 1个 DATA 帧 | 1个 DATA 帧 | 普通 RPC；本项目所有 Dubbo 调用都是此类 |
| **Client Streaming（客户端流）** | N个 DATA 帧 | 1个 DATA 帧 | 大文件分块上传、批量数据上报 |
| **Server Streaming（服务端流）** | 1个 DATA 帧 | N个 DATA 帧 | 实时数据订阅、大结果集分批返回 |
| **Bidirectional Streaming（双向流）** | N个 DATA 帧 ↔ | ↔ N个 DATA 帧 | 实时聊天、音视频、游戏帧同步 |

```java
// Unary（默认，最常用）
MerchantDTO result = rpcMerchantService.getMerchantByUserId(userId);

// Server Streaming（流式返回，Java Interface 模式，Dubbo 3.2+）
StreamObserver<MerchantDTO> observer = new StreamObserver<>() {
    public void onNext(MerchantDTO value)  { /* 每收到一条响应处理 */ }
    public void onCompleted()              { /* 流结束 */ }
    public void onError(Throwable t)       { /* 出错 */ }
};
rpcMerchantService.streamMerchants(filter, observer);
```

**本项目只使用 Unary 模式**，其余三种是 Triple 协议的能力储备，了解边界即可。

---

### 11.7 Netty 在 Triple 中扮演什么角色

常见误解："Netty 负责 Dubbo RPC 通信"——这个说法不准确。更准确的表述是：

**Netty 只负责 I/O 层（TCP 字节 → HTTP/2 帧），完全不理解 RPC 语义。**

```
收到 TCP 字节流
    ↓
[Http2FrameCodec]（Netty 内置 ChannelHandler）
  将字节流解码为 HTTP/2 帧对象（HEADERS / DATA / TRAILERS / SETTINGS...）
  Netty 的工作到此结束——它只处理帧的结构，不知道帧里装的是什么业务数据
    ↓
[TripleServerTransport]（Dubbo 注册在 Netty Pipeline 中的 Handler）
  读取 HEADERS 帧：取出 :path，解析接口全限定名 + 方法名
  读取 DATA 帧：剥掉 gRPC 5字节前缀，拿到序列化后的参数字节
  读取 TRAILERS 帧（如果有）：判断是否出错
    ↓
[Dubbo 业务层]
  按接口名 + 方法名，用反射找到 @DubboService 实现类
  反序列化参数字节（Hessian2 / Protobuf / TripleRequestWrapper）
  调用真实 Service 方法，拿到返回值
  序列化返回值 → 写 DATA 帧 + Trailers 帧回给 Consumer
```

**Netty 不知道 gRPC 协议、不知道接口名/方法名、不知道 Hessian2 是什么。** 它只提供了高性能的 NIO 事件驱动 I/O 框架和 HTTP/2 帧编解码器（`Http2FrameCodec`）。Dubbo 把自己的业务 Handler（`TripleServerTransport`）挂在 Netty 的 `ChannelPipeline` 上，Netty 把帧对象交给 Dubbo，Dubbo 再做 RPC 语义的解析和调用。

**一句话记忆**：Netty 是"快递员"，负责收发包裹（帧）；包裹里装什么（RPC 接口/方法/参数）是 Dubbo 自己的事。

---

## 十二、关键概念速查

| 概念 | 一句话说明 |
|------|-----------|
| `@DubboService` | Provider 侧注解，将实现类发布为 Dubbo 服务并注册到 Nacos |
| `@DubboReference` | Consumer 侧注解，注入 Dubbo 动态代理（非 final 字段）|
| `@EnableDubbo` | 启动类注解，扫描 `@DubboService` 并开启 Dubbo 自动配置 |
| `RegistryDirectory` | Consumer 侧核心：订阅 Nacos 变更，维护 URL→Invoker 映射表 |
| `Invoker` | 封装一条到 Provider 的 Netty 长连接，是 Dubbo 调用的最小单元 |
| `Router` | 路由过滤层，从全量 Invoker 中按条件选出子集（灰度/条件路由）|
| `LoadBalance` | 从 Router 子集中选一个 Invoker（随机/轮询/最少活跃）|
| `Cluster` | 集群容错层，决定失败时如何处理（failover/failfast...）|
| `Filter` | AOP 横切层，在 Invoker 调用前后执行（限流/日志/监控）|
| `SPI` | Dubbo 插件化核心，所有组件均可按名替换，文件放 META-INF/dubbo/ |
| `@Adaptive` | SPI 自适应扩展：根据 URL 参数在运行时动态选择实现 |
| `version` | 服务版本，Consumer 和 Provider 必须匹配，用于灰度/多版本并存 |
| `group` | 服务分组，同一接口多实现时按业务路由 |
| `timeout` | 超时（ms），Consumer 侧优先级高于 Provider |
| `retries` | 重试次数，写操作必须设 0 防重复执行 |
| `failover` | 失败自动切换重试（默认，只用于幂等查询）|
| `failfast` | 快速失败不重试（写操作必用）|
| `Triple` | Dubbo3 新协议，HTTP/2 + gRPC 互通 + 穿透 HTTP 网关 |
| `Hessian2` | Dubbo 默认序列化，二进制，DTO 须实现 Serializable |
| 应用级注册 | Dubbo 3 默认：以应用为单位注册 Nacos，接口信息存元数据中心 |
| 接口级注册 | Dubbo 2 方式：每个接口单独注册，数据量大，大规模场景有推送风暴风险 |
| 磁盘缓存 | Consumer 收到地址列表后写入本地文件，Nacos 宕机时作兜底 |
| 优雅下线 | 停机时 ShutdownHook 主动向 Nacos 注销，Consumer 立即感知，不需等 30s |

---

## 十三、实际遇到的问题

### Dubbo 3.3.x + Nacos 2.x：序列化安全 STRICT 模式导致 RPC 调用 500

**现象**

服务启动正常，部分 Dubbo 调用偶发或必现 500，日志中出现：

```
[Serialization Security] Serialized class com.google.protobuf.GeneratedMessageLite$SerializedForm
is not in allow list. Current mode is `STRICT`, will disallow to deserialize it by default.
```

**根因：三个机制叠加触发**

**① Dubbo 3.2+ 的序列化安全检查**

Dubbo 引入了反序列化白名单（allowlist）防御 Java 反序列化攻击。STRICT 模式下，白名单以外的任何类都会被拒绝，直接抛 `IllegalArgumentException`，导致调用失败。

Java 反序列化漏洞的原理：攻击者构造恶意字节流，反序列化时借助类库中已有的"Gadget Chain"（如 Apache Commons Collections、Spring 框架中的合法类）触发任意代码执行。STRICT 模式的白名单就是阻断这条路径——你的类不在我的名单里，我就不反序列化它。

**② Nacos 2.x 内部使用 Protobuf**

Nacos 2.x 将 Server 与 Client 之间的通信从 HTTP 升级为 gRPC（Protobuf）。Dubbo 在做应用级服务发现时，需要从 Nacos 拉取 Provider 的接口元数据（revision 版本对应的接口详情），这个拉取过程中会接触到 Nacos 内部的 Protobuf 对象。

**③ Protobuf 的 Java 序列化代理**

Protobuf 生成的消息类都定义了 `writeReplace()` 方法：

```java
// Protobuf 生成的消息类内部
protected Object writeReplace() throws ObjectStreamException {
    return new SerializedForm(this);
}
```

这是 Java 序列化协议的代理模式：对象被序列化时，如果定义了 `writeReplace()`，框架会把它替换成 `writeReplace()` 返回的对象来序列化。Protobuf 用这个机制保证跨版本兼容性。

**三者叠加的结果：**

```
Dubbo 内部元数据服务拉取 Nacos 数据（用 Hessian2 序列化）
    → 遇到 Nacos 的 Protobuf 消息对象
    → 触发 writeReplace()，返回 GeneratedMessageLite$SerializedForm
    → Hessian2 尝试序列化 SerializedForm
    → STRICT 模式检查：不在白名单 → 抛 IllegalArgumentException → 调用失败 → 500
```

**为什么是偶发的？**

Dubbo 会缓存服务实例的元数据。缓存命中时，不需要重新从 Nacos 拉取 Protobuf 元数据，调用成功；缓存失效（服务实例变更、revision 更新、首次启动后一段时间）时，触发元数据刷新，命中 Protobuf 序列化路径，调用失败。这就是同一个接口时好时坏的原因。

**修复**

在 Consumer（mhp-booking）和 Provider（mhp-account）的 Dubbo 配置里加一行，将 STRICT 降级为 WARN：

```yaml
dubbo:
  application:
    name: mhp-booking
    serialize-check-status: WARN   # 加这一行
```

`WARN` 模式保留安全检查逻辑，检测到不在白名单的类时只打警告日志，不抛异常，让调用继续。`GeneratedMessageLite$SerializedForm` 本身没有已知的 Gadget Chain，降级为警告是合理的工程折中。

更精确的方案是创建 `src/main/resources/security/serialize.allowlist`，只放行这一个类：

```
com.google.protobuf.GeneratedMessageLite$SerializedForm
```

**为什么换 Zookeeper 可以根本上避免此问题**

| 注册中心 | 内部通信协议 | 是否引入 Protobuf |
|----------|------------|-----------------|
| Nacos 2.x | gRPC（Protobuf） | 是 → 触发上述问题 |
| Zookeeper | Jute（自研二进制，无 Protobuf）| 否 → 不存在此问题 |

Zookeeper 与 Dubbo 的元数据交换不涉及 Protobuf，STRICT 模式的检查不会被触发。但项目中 Nacos 同时承担配置中心职责，换 Zookeeper 意味着引入额外的中间件，代价远大于加一行配置，不值得。

---

### 安装 Docker 的服务器：Dubbo 注册 IP 错误导致 RPC 超时

**现象**

服务启动正常，Nacos 控制台也显示服务已注册，但 Consumer 调用 Provider 时必现超时：

```
Timeout after 5000ms, Failed to send message to /172.18.0.1:PORT
```

**服务器上的四种 IP**

理解问题前，先搞清楚服务器上同时存在的几个地址：

```
外网用户
    │
    ▼
62.234.139.139（公网 IP）—— 全球可访问，Nginx 监听此 IP
    │
    ▼
10.2.0.6（内网 IP）—— 云厂商分配的真实网卡，同 VPC 内机器可互访
    │
    ├── 127.0.0.1（回环地址）—— 仅本机进程可访问
    │
    └── 172.18.0.1（Docker 网桥）—— Docker 给宿主机建的虚拟网卡，
                                     供容器与宿主机通信使用
```

| IP | 名字 | 谁能访问 |
|---|---|---|
| `62.234.139.139` | 公网 IP | 全球任何人 |
| `10.2.0.6` | 内网 IP | 同 VPC 内机器（含自身） |
| `127.0.0.1` | 回环地址 | 仅本机进程 |
| `172.18.0.1` | Docker 网桥 | Docker 容器与宿主机之间 |

**根因：Dubbo 自动选网卡选错了**

Dubbo 启动时会自动扫描本机所有网卡，按规则选一个 IP 注册到 Nacos。安装 Docker 后，宿主机多出了 `docker0` 虚拟网卡（`172.18.0.1`），Dubbo 的选择算法在某些系统上会选中它：

```
account 注册到 Nacos："我在 172.18.0.1:PORT"

booking 问 Nacos："account 在哪？"
    → Nacos 回答：172.18.0.1:PORT

booking 尝试连接 172.18.0.1:PORT
    → Docker 的 iptables 规则干扰正常流量
    → 连接超时 → RPC 调用失败 → 500
```

`172.18.0.1` 虽然在宿主机上存在，但 Docker 为容器网络配置了复杂的 `iptables` 转发规则，普通进程之间的连接经过这个地址时可能被拦截或绕路，导致超时。

**修复**

**不能用 `protocol.host`**，原因见下方"踩坑记录"。正确做法是用 `DUBBO_IP_TO_REGISTRY` 环境变量，只覆盖注册地址，不影响绑定地址：

```bash
DUBBO_IP_TO_REGISTRY=10.2.0.6 java -jar mhp-account.jar
```

同时清掉 Dubbo 的磁盘缓存，防止 Consumer 使用旧地址：

```bash
rm -f ~/.dubbo/dubbo-registry-*.cache
```

修复后的调用链：

```
account 注册到 Nacos："我在 10.2.0.6:PORT"（绑定地址仍为 0.0.0.0）

booking 问 Nacos → 拿到 10.2.0.6:PORT → 连接成功
```

**为什么用内网 IP 而不是公网 IP 或 127.0.0.1**

- `127.0.0.1`：只有本机能用，一旦服务拆分到不同机器就会失效
- `62.234.139.139`（公网 IP）：流量绕出公网再回来，延迟高且可能被防火墙拦截
- `10.2.0.6`（内网 IP）：同 VPC 内直连，延迟低，多机部署时也能正常工作，是正确选择

**踩坑：`protocol.host` 会同时影响绑定地址，导致服务无法注册**

第一次尝试用 `protocol.host: 10.2.0.6` 修复，结果更糟：

```yaml
# 错误写法
dubbo:
  protocol:
    host: 10.2.0.6  # ← 看起来只是改注册地址，实际上还改了绑定地址
```

`protocol.host` 同时控制两件事：
1. 注册到 Nacos 的 IP（想改的）
2. Dubbo 服务器 socket 的绑定地址（不该动的）

Dubbo 原本绑定 `0.0.0.0`（监听所有网卡），改成绑定特定 IP 后，如果该 IP 在当前系统上绑定异常，Dubbo 服务端口无法启动，导致服务根本没有注册到 Nacos（`hosts: []`）。Consumer 拿不到新地址，转而使用磁盘缓存里的旧地址（上一次运行的随机端口），连过去协议对不上，出现 `Cannot recognize protocol` 错误。

| 配置方式 | 影响注册地址 | 影响绑定地址 | 结论 |
|---|---|---|---|
| `protocol.host: 10.2.0.6` | ✅ | ✅（危险） | 不要用 |
| `DUBBO_IP_TO_REGISTRY=10.2.0.6` | ✅ | ❌（安全） | 正确方式 |
