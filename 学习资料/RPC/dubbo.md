# Dubbo 系统笔记

---

## 一、RPC 与 Dubbo 概述

### 1.1 从 RPC 到微服务的演化

**RPC（Remote Procedure Call）**：目标是让调用远程服务像调用本地方法一样，开发者不用关心网络细节。

跨进程调用只能走网络，因此需要解决三个问题：**通信方式**（TCP/HTTP）、**协议**（消息格式）、**序列化**（数据如何编码为字节流）。

**SOA**：RPC 架构的演化，解决了两个问题：
1. 调用失败怎么办（需要容错机制）
2. 某个模块访问量大但无法单独扩容

SOA 的解法是将模块独立为进程（服务），可以单独水平扩展。

**微服务**：SOA 的进一步升级。彻底去掉"子系统"概念，所有功能都服务化，每个服务单独部署、单独扩缩容。

### 1.2 异构系统的 RPC 调用

不同编程语言的数据类型不兼容，通过 **IDL（接口定义语言）** 定义统一中间类型——如 Protobuf（gRPC/Triple 使用）、Thrift IDL——各语言各自生成对应的代码，实现跨语言 RPC。

### 1.3 Dubbo 的六个核心角色

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

### 1.4 一次完整 RPC 调用总览

```
Consumer 调用接口方法
    ↓ 动态代理拦截，创建 RpcInvocation
    ↓ 从注册中心维护的地址列表，经路由过滤 + 负载均衡选出一个 Provider
    ↓ 序列化（方法名 + 参数 → 字节流）
    ↓ Netty 长连接发送请求（TCP / HTTP/2）
    ↓ Provider 接收 → 反序列化 → 反射调用真实实现
    ↓ 结果序列化 → 网络返回
    ↓ Consumer 代理反序列化 → 返回给调用方
```

---

## 二、快速接入

### 2.1 接口定义（mhp-common，Provider/Consumer 共同依赖）

```java
// API 契约：定义在公共模块，Provider 实现，Consumer 引用
public interface RpcMerchantService {
    MerchantDTO getMerchantByUserId(Long userId);
    void updateMerchantScore(Long merchantId, MerchantScoreUpdateDTO dto);
}
```

### 2.2 Provider 端：@DubboService 暴露服务

```java
@DubboService(version = "1.0.0")   // 发布为 Dubbo 服务并注册到 Nacos
@Service                            // 同时加 Spring @Service 保证兼容性
@RequiredArgsConstructor
public class RpcMerchantServiceImpl implements RpcMerchantService {
    private final MerchantService merchantService;

    @Override
    public MerchantDTO getMerchantByUserId(Long userId) {
        // 查 DB，转换为 DTO 返回（DTO 必须实现 Serializable，Hessian2 要求）
    }
}
```

### 2.3 Consumer 端：@DubboReference 注入代理

```java
@Service
@RequiredArgsConstructor
public class BookingServiceImpl {

    private final AccountFeignClient accountFeignClient;  // OpenFeign（final，构造器注入）

    @DubboReference(version = "1.0.0")                   // Dubbo 代理（非 final，Dubbo 自己注入）
    private RpcMerchantService rpcMerchantService;

    public void someMethod() {
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
    }
}
```

**为什么 `@DubboReference` 字段必须是非 final？**

Dubbo 用自己的字段注入机制（扫描注解后通过反射赋值），不通过 Spring 构造器注入。`final` 字段只能在构造器中赋值，Lombok 的 `@RequiredArgsConstructor` 不会包含 Dubbo 代理字段，所以必须声明为普通字段。

### 2.4 启动类

```java
@SpringBootApplication
@EnableFeignClients(basePackages = "com.mhp.booksystem.feign")
@EnableDubbo(scanBasePackages = "com.mhp.booksystem")  // 扫描 @DubboService 并发布
public class AccountApplication { ... }
```

`@EnableDubbo` 默认扫描启动类所在包及其子包，也可在 yaml 中配置：
```yaml
dubbo:
  scan:
    base-packages: com.mhp.booksystem
```

### 2.5 application.yaml 配置

```yaml
dubbo:
  application:
    name: mhp-account     # 服务名，注册到 Nacos 时的标识（Dubbo 3 应用级注册用）
    qos-enable: false     # 关闭 QoS 端口（默认 22222，多服务同机容易冲突）
  protocol:
    name: tri             # Triple 协议（HTTP/2，Dubbo 3 推荐）
    port: -1              # 随机端口，避免与 Spring Boot HTTP 8081 冲突
  registry:
    address: nacos://127.0.0.1:8848   # 复用已有 Nacos，不引入新中间件
```

这几行配置与下一章 Nacos 的关联：
- `dubbo.application.name` → 注册到 Nacos 的 serviceName
- `dubbo.protocol.port: -1` → 随机绑定端口，实际端口写入 Nacos 实例 metadata
- `dubbo.registry.address` → 注册请求和订阅请求都发往这个 Nacos 地址

**QoS 端口冲突**：Dubbo 的 QoS 管理端口默认 22222，多个 Dubbo 服务在同一台机器上运行时会冲突，务必关闭或分配不同端口。

### 2.6 直连模式（绕过注册中心，仅用于本地调试）

```java
// 直接指定 Provider 的 IP:Port，完全绕过 Nacos
@DubboReference(url = "tri://192.168.1.10:20880", version = "1.0.0")
private RpcMerchantService rpcMerchantService;
```

等价于 OpenFeign 的 `@FeignClient(url = "http://ip:port")`。共同缺陷：地址硬编码，无服务发现，无法动态扩缩容，无法感知故障。**仅用于开发联调，不具备生产能力。**

---

## 三、与 Nacos 注册中心的配合

### 3.1 注册中心解决的两个核心问题

1. **服务发现**：Consumer 无需知道 Provider 的具体 IP，只需知道服务名，框架自动解析
2. **动态感知**：Provider 集群扩缩容、故障，Consumer 无需重启，自动更新可用列表

### 3.2 Provider 向 Nacos 注册的完整步骤

```
java -jar mhp-account.jar
    ↓
@EnableDubbo 触发 DubboComponentScanRegistrar
  → 扫描 @DubboService 类，为每个类创建 ServiceConfig（接口名、版本、协议、超时等）
    ↓
Spring 容器启动完成后，DubboBootstrap.start() 触发
    ↓
① Protocol 层绑定端口（tri 协议随机选可用端口，如 50051）
   → Netty 开始监听，等待 Consumer 连接
    ↓
② 向 Nacos 注册（应用级注册，Dubbo 3 默认）：
   POST http://127.0.0.1:8848/nacos/v1/ns/instance
     serviceName = "mhp-account"
     ip = "10.2.0.6", port = 50051
     metadata = {
       "dubbo.endpoints": [{"port":50051,"protocol":"tri"}],
       "dubbo.metadata.revision": "43143d5...",
       "meta-v": "2.0.0"
     }
    ↓
③ 接口元数据发布到元数据中心（storage-type=local 时存本地文件）
   Consumer 需要时从 Provider 的 /dubbo/metadata 接口拉取
    ↓
④ 启动心跳线程：每 5 秒 PUT /nacos/v1/ns/instance/beat
```

### 3.3 注册数据结构：Dubbo 2 vs Dubbo 3

#### Dubbo 2：接口级注册

每个接口在 Nacos 单独注册一条数据：

```
Nacos 服务名：providers:com.mhp.booksystem.rpc.RpcMerchantService:1.0.0:
实例 URL：tri://192.168.1.10:20880/com.mhp.booksystem.rpc.RpcMerchantService
  ?application=mhp-account&version=1.0.0&methods=getMerchantByUserId,...
```

**问题**：100 个接口 → Nacos 存 100 条记录。大规模场景（数百服务 × 数百接口）导致推送风暴。

#### Dubbo 3：应用级注册（默认）

以应用为单位注册，接口信息从 Nacos 中分离：

```
Nacos 服务名：mhp-account
实例信息：IP: 192.168.1.10, Port: 50051, Protocol: tri   ← 随机端口，实际值写入 Nacos
Metadata: { "revision": "abc123" }   ← 接口元数据版本摘要

接口/方法详情 → 独立的元数据中心（Nacos / Redis / ZooKeeper）
```

100 个接口的应用，Nacos 中只有 **1 条**实例记录。

| 维度 | Dubbo 2（接口级） | Dubbo 3（应用级） |
|------|----------------|----------------|
| Nacos 数据量 | 接口数 × 实例数 | 实例数（减少 N 倍）|
| 推送粒度 | 接口级（量大） | 实例级（量小）|
| 与 Spring Cloud 兼容 | 不兼容 | 兼容（同一 serviceName）|

### 3.4 Consumer 端服务发现机制

Consumer 不是每次调用都去 Nacos 查询，而是**订阅一次，本地维护实时列表**：

```
Consumer 启动
    ↓
向 Nacos 订阅目标服务，建立长连接 / 长轮询监听
    ↓
Nacos 返回初始实例列表（全量推送）
    ↓
Dubbo 为每个实例创建 Invoker（封装一条 Netty 长连接，提前建立复用）
RegistryDirectory 持有 {URL → Invoker} 映射表 = 当前所有可用 Provider 的连接池
    ↓
Nacos 实时推送增量变更：
  Provider A 上线 → 新增 Invoker A + 建立新 Netty 连接
  Provider B 下线 → 移除 Invoker B + 关闭对应连接
```

**RegistryDirectory** 是 Consumer 侧核心：订阅 Nacos 变更，维护 URL→Invoker 映射，增量更新（不重建全量连接）。

**本地磁盘缓存（Nacos 宕机兜底）**：

```
~/.dubbo/dubbo-registry-mhp-account-127.0.0.1:8848.cache
```

Nacos 宕机时：
- **已有连接**：Consumer 与 Provider 直连的 Netty 长连接不经过 Nacos，不受影响
- **新启动的 Consumer**：从磁盘缓存读取上次地址列表，仍可正常调用

### 3.5 心跳机制与故障摘除

```
Provider 正常运行：每 5 秒向 Nacos 发心跳

Provider 异常宕机（进程 crash，无法主动注销）：
  超过 15 秒未收到心跳 → 标记 unhealthy（保留记录）
  超过 30 秒未收到心跳 → 从实例列表摘除 → 推送变更给所有 Consumer
  Consumer 收到推送       → RegistryDirectory 移除 Invoker，后续不再打过去
```

**感知延迟最长 30 秒**，这是 `failover`（自动重试切换）存在意义的根本原因——这 30 秒内请求打到宕机的 Provider 会失败，`failover` 检测到失败后自动切换到另一个 Invoker 重试。

### 3.6 优雅下线（正常停机）

```
Provider 收到 SIGTERM
    ↓
Dubbo ShutdownHook（JVM 关闭钩子）触发：
  ① 停止接受新请求（关闭监听端口）
  ② 等待当前请求完成（最长 shutdownTimeout，默认 10s）
  ③ 主动向 Nacos 注销（deregister）
    ↓
Nacos 立即摘除 → 推送给所有 Consumer
    ↓
Consumer RegistryDirectory 立即移除该 Invoker → 后续请求零感知切换
```

优雅下线 + 注册中心主动推送 = 正常停机时 Consumer 几乎感知不到。

### 3.7 本项目：Dubbo 与 Spring Cloud 共用 Nacos

Dubbo 注册中心复用 Spring Cloud 已有的 Nacos（`nacos://127.0.0.1:8848`），不引入新中间件。Nacos 上同时存在两类注册信息：

| 类型 | 服务名 | 协议 | 用于 |
|------|--------|------|------|
| Spring Cloud 注册 | `mhp-account` | HTTP/8081 | OpenFeign 负载均衡 |
| Dubbo 注册 | `mhp-account` | tri/50051 | Dubbo RPC 服务发现 |

Nacos 通过实例 metadata 中的 `dubbo.endpoints` 字段区分两者。OpenFeign 的 LoadBalancer 只关注 HTTP 实例，Dubbo 的 RegistryDirectory 只处理 tri:// 实例，两套机制互不干扰。

---

## 四、内部调用层次（七层模型）

表面上看是一次普通的方法调用，实际上经过了七层处理：

```
Consumer 代码：rpcMerchantService.getMerchantByUserId(userId)
    ↓
[Proxy 层] JDK 动态代理拦截
  构建 RpcInvocation（接口名、方法名、参数数组）
    ↓
[Cluster 层] 集群容错（FailoverCluster / FailfastCluster...）
  决定失败时如何处理，向 Directory 请求当前可用 Invoker 列表
    ↓
[Directory 层] RegistryDirectory
  返回当前所有可用 Invoker（从 Nacos 订阅的实时列表）
    ↓
[Router 层] 路由过滤链
  TagRouter：按标签隔离（灰度，v2 流量只打 v2 标签节点）
  ConditionRouter：按条件路由（region=bj 只打 beijing 机房）
  → 输出：满足条件的 Invoker 子集
    ↓
[LoadBalance 层] 负载均衡
  从子集中按策略选出一个 Invoker
    ↓
[Filter 链] AOP 切面（调用前后各执行一遍）
  Consumer 侧：ConsumerContextFilter（传递 traceId）/ ActiveLimitFilter（并发限制）/ MonitorFilter
  Provider 侧：ExecuteLimitFilter（线程数上限）/ TpsLimitFilter / AccessLogFilter
    ↓
[Protocol/Invoker 层] 实际协议执行
  序列化请求体（Hessian2 / Protobuf）
  通过 Netty Channel 发送二进制帧
  等待响应（同步阻塞 / 异步 CompletableFuture）
  反序列化响应 → 返回
    ↓
Netty Channel（TCP / HTTP/2 长连接）→ Provider
```

Router 做**过滤**（全量 → 子集），LoadBalance 做**选择**（子集 → 一个），Cluster 做**容错**（选定的那个失败后怎么办）——三者职责不重叠。**所有七层全部通过 SPI 可替换**，这是 Dubbo 高扩展性的架构基础。

### 同步与异步

Dubbo 底层**总是异步的**（Netty NIO 天然异步），同步调用只是在 CompletableFuture 上阻塞等待：

```java
// 默认同步：框架内部 CompletableFuture.get(timeout)，阻塞当前线程
MerchantDTO result = rpcMerchantService.getMerchantByUserId(userId);

// 显式异步（Dubbo 3）：发出请求立即返回，不阻塞当前线程
CompletableFuture<MerchantDTO> future = RpcContext.getClientAttachment()
    .asyncCall(() -> rpcMerchantService.getMerchantByUserId(userId));
future.thenAccept(merchant -> { /* 异步回调 */ });
```

---

## 五、服务治理

### 5.1 负载均衡策略

| 策略 | 算法 | 适用场景 |
|------|------|---------|
| `random`（默认） | 按权重随机 | 通用，机器性能相近时均衡 |
| `roundrobin` | 加权轮询（Smooth Weighted RR）| 通用，各机权重不同时 |
| `leastactive` | 最少活跃数（正在处理请求最少的优先）| 机器性能差异大 |
| `consistenthash` | 一致性哈希（相同参数总打同一台）| 需要会话粘性，如本地缓存 |
| `shortestresponse` | 最短响应时间优先 | 对延迟极敏感 |

```java
@DubboReference(version = "1.0.0", loadbalance = "leastactive")
private RpcMerchantService rpcMerchantService;
```

### 5.2 集群容错策略

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| `failover`（默认） | 失败自动切换到另一台重试（默认重试 2 次） | 读操作，幂等接口 |
| `failfast` | 失败立即报错，不重试 | 写操作，非幂等接口 |
| `failsafe` | 失败忽略，返回空结果 | 日志上报、审计等次要操作 |
| `failback` | 失败后异步定期重试 | 最终一致性场景 |
| `forking` | 并发调用多台，有一台成功即返回 | 对延迟极敏感，可接受资源浪费 |
| `broadcast` | 广播所有 Provider，有一台失败即整体失败 | 刷新所有节点本地缓存 |

```java
// 写操作用 failfast，防止重试导致重复写入
@DubboReference(version = "1.0.0", cluster = "failfast")
private RpcMerchantService rpcMerchantService;
```

### 5.3 超时与重试

```java
// Provider 侧：允许方法执行的最长时间
@DubboService(version = "1.0.0", timeout = 3000)

// Consumer 侧：等待响应的最长时间（Consumer 优先级高于 Provider）
@DubboReference(version = "1.0.0", timeout = 2000, retries = 2)
```

**优先级**：Consumer 方法级 > Consumer 接口级 > Provider 方法级 > Provider 接口级 > 全局配置。

**核心原则**：`retries=0` 等价于 `failfast`。**写操作必须设 `retries=0`**，否则自动重试可能导致数据重复写入。

**面试要点**：`getMerchantByUserId` 是幂等查询 → `failover`（默认）；`updateMerchantScore` 是写操作 → `failfast` 或 `retries=0`。

---

## 六、高级特性

### 6.1 版本与分组

**版本（version）**：同一接口多版本并存，用于灰度发布和不兼容升级的平滑过渡。

```java
@DubboService(version = "1.0.0")
public class RpcMerchantServiceImplV1 implements RpcMerchantService { ... }

@DubboService(version = "2.0.0")
public class RpcMerchantServiceImplV2 implements RpcMerchantService { ... }

@DubboReference(version = "1.0.0")   // 只调旧版
@DubboReference(version = "*")       // 随机调任意版本（灰度）
```

**分组（group）**：同一接口不同业务实现，按 group 隔离路由。

```java
@DubboService(group = "vip")
public class VipMerchantServiceImpl implements RpcMerchantService { ... }

@DubboService(group = "normal")
public class NormalMerchantServiceImpl implements RpcMerchantService { ... }

@DubboReference(group = "vip")   // 只调用 vip 分组
```

### 6.2 序列化

| 方式 | 格式 | 性能 | 跨语言 | Dubbo 支持 |
|------|------|------|--------|-----------|
| Hessian2 | 二进制 | 高 | 有限 | 默认 |
| Protobuf | 二进制 | 极高 | 完整 | Triple 协议支持 |
| JSON（Jackson）| 文本 | 中 | 完整 | 支持 |
| Kryo | 二进制 | 极高 | Java 专属 | 需引入 `dubbo-serialization-kryo` |
| Java 原生 | 二进制 | 低 | Java 专属 | 支持（不推荐）|

**Hessian2 要求**：传输的 DTO 类必须实现 `java.io.Serializable` 并声明 `serialVersionUID`，否则反序列化失败。

### 6.3 SPI 扩展机制

SPI（Service Provider Interface）是接口与实现解耦的标准机制。Dubbo 用自己增强版的 SPI 驱动整个框架，所有核心组件（协议、序列化、负载均衡、集群容错、注册中心）都是 SPI 扩展点。

| 对比项 | Java SPI | Dubbo SPI |
|--------|---------|-----------|
| 加载方式 | 全部加载 | 按名字懒加载（只加载用到的）|
| 扩展点 | 不支持 IoC/AOP | 支持 IoC 注入、AOP Wrapper 包装 |
| 自适应扩展 | 不支持 | `@Adaptive`：根据 URL 参数运行时动态选实现 |
| Activate 机制 | 不支持 | `@Activate`：根据条件自动激活 Filter |

扩展文件放在：`META-INF/dubbo/`（自定义）或 `META-INF/dubbo/internal/`（内置）。

自定义扩展示例：

```java
// 1. 实现扩展接口
public class MyLoadBalance implements LoadBalance {
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        return invokers.get(0);
    }
}

// 2. META-INF/dubbo/org.apache.dubbo.rpc.cluster.LoadBalance 文件内容：
myLB=com.example.MyLoadBalance

// 3. 使用
@DubboReference(loadbalance = "myLB")
```

---

## 七、传输协议：Triple 与 HTTP/2

### 7.1 三种传输方式的底层格式

#### 老 Dubbo 协议（`dubbo://`）：纯 TCP，完全没有 HTTP

```
TCP 字节流（Dubbo 自定义帧）：
┌──────────┬──────────┬──────────┬────────────────────────────────────┐
│ 魔数     │ 标志位   │ 请求 ID  │  正文（接口名+方法名+参数，二进制） │
│ 0xdabb   │ 1字节    │ 8字节    │  N字节（Hessian2 序列化）           │
└──────────┴──────────┴──────────┴────────────────────────────────────┘
```

Nginx 完全不认识此格式，无法转发。

#### HTTP/1.1（OpenFeign）：纯文本，人眼可读

```
GET /api/user/123 HTTP/1.1
Host: mhp-account
Content-Type: application/json

{"id": 123}
```

**同一 TCP 连接上请求必须串行**：发完一个等响应，再发下一个（队头阻塞）。

#### HTTP/2（Triple 使用）：二进制帧 + 多路复用

```
HTTP/2 帧结构：
┌──────────────┬──────────┬──────────┬─────────────────────┐
│ 帧长度  3字节│ 帧类型   │ Stream号 │  有效载荷（二进制）  │
└──────────────┴──────────┴──────────┴─────────────────────┘
```

**Stream** 是核心：一个 TCP 连接上同时存在多个 Stream，各自并行，互不阻塞：

```
HTTP/1.1（串行）：──请求1──▶ 等 ──▶ 响应1 ──请求2──▶ 等 ──▶ 响应2──

HTTP/2（并发）：── Stream1 请求 ──▶
               ── Stream2 请求 ──▶  ◀── Stream1 响应 ──
               ── Stream3 请求 ──▶  ◀── Stream3 响应 ──
                                    ◀── Stream2 响应 ──
```

### 7.2 Triple 实际发什么

Triple 遵循 gRPC 协议规范，借 HTTP/2 的壳传 Dubbo 的二进制数据：

```
HTTP/2 HEADERS 帧：
  :method = POST       ← 永远是 POST（总有请求体）
  :path = /com.mhp.booksystem.rpc.RpcMerchantService/getMerchantByUserId
  content-type = application/grpc+proto

HTTP/2 DATA 帧：
  [Hessian2 或 Protobuf 序列化的参数值，二进制]
```

Provider 从 path 里解出接口名和方法名，从 DATA 帧反序列化参数，再用反射调用真实实现。

### 7.3 为什么 Triple 选 HTTP/2 而不是继续用私有 TCP

核心原因只有一个：**HTTP/2 是 Nginx、Envoy、API Gateway 等基础设施都认识的标准协议。**

老 `dubbo://` 是私有 TCP，Nginx 不认识，无法转发，也无法做 TLS 终止，在 Service Mesh（如 Istio）环境里寸步难行。换成 HTTP/2 后，整个云原生基础设施可以无缝处理 Dubbo 流量。

同时，gRPC 用的也是 HTTP/2 + Protobuf，Triple 遵循同一规范，所以 Dubbo 服务可以直接被 gRPC 客户端（Go/Python/Node.js）调用，实现跨语言互通。

| | 老 Dubbo（dubbo://）| Triple（HTTP/2）|
|--|------------------|----------------|
| 底层传输 | 裸 TCP | HTTP/2 |
| 能过 Nginx | ✗ | ✓ |
| 与 gRPC 互通 | ✗ | ✓ |
| 多路复用 | 自己实现 | HTTP/2 原生支持 |

`port: -1` 的原理：Dubbo 启动时随机找一个可用端口绑定，把**实际端口**注册到 Nacos。Consumer 从 Nacos 拿到的地址里包含真实端口，无需手动配置。

---

## 八、Dubbo vs OpenFeign

### 8.1 本质澄清：两者都在 Provider 端执行代码

OpenFeign 和 Dubbo 干的事情在本质上完全相同：

```
① 把"调什么方法、传什么参数"打包
② 通过网络发给 Provider
③ Provider 执行真实代码，把结果打包
④ 结果通过网络返回给 Consumer
```

反射（`method.invoke()`）发生在 **Provider 端**。Consumer 拿到的永远是运行好的结果，不是代码本身。OpenFeign 的 Provider 端由 Spring MVC 路由到 `@GetMapping` 方法，机制不同但位置一样。

> OpenFeign 广义上算 RPC（远程调用、对调用方透明），但业界习惯上"RPC 框架"特指使用二进制协议的（Dubbo、gRPC、Thrift），OpenFeign 通常被叫"声明式 HTTP 客户端"。这是分类习惯，不影响本质。

### 8.2 协议对比

| 对比维度 | Dubbo3 Triple | OpenFeign HTTP REST |
|---------|--------------|---------------------|
| **协议** | HTTP/2 | HTTP/1.1 |
| **序列化** | Hessian2 / Protobuf（二进制）| JSON（文本，可读）|
| **连接模型** | 长连接复用（Netty Channel）| 短连接或 HTTP Keep-Alive |
| **性能** | 更高（二进制 + 多路复用 + Header 压缩）| 相对低（文本 + 连接开销）|
| **接口契约** | 强类型 Java 接口，编译期检查 | HTTP URL + JSON，运行时发现错误 |
| **错误传播** | 异常直接透传 Consumer | 需解包响应体，手动判断 code |
| **跨语言** | Triple 与 gRPC 互通（Protobuf）| 天然跨语言（任何语言都能发 HTTP）|

### 8.3 本项目的分工原则

**选 Dubbo（强业务依赖）的 3 个调用：**

| 调用 | 调用方 | 选 Dubbo 的理由 |
|------|--------|----------------|
| `getMerchantByUserId` | mhp-booking | 商家身份前置校验，失败则整个操作终止，不可降级 |
| `updateMerchantScore` | mhp-social | 唯一跨服务写操作，强一致性要求，接口契约必须严格 |
| `getBookingById` | mhp-social | 评价/投诉的业务前置条件，失败则拒绝业务 |

**保留 Feign（弱业务依赖）的 4 个调用：**

| 调用 | 调用方 | 保留 Feign 的理由 |
|------|--------|-----------------|
| `batchGetUsers` | booking / social | 展示数据，失败可降级为占位符 |
| `batchGetMerchants` | booking / social | 展示数据，未来可被 Canal CDC 本地缓存取代 |
| `getMerchant` | social 投诉流程 | 辅助信息查询，失败可降级 |
| `getUser` | social 消息流程 | 存在性检查，可接受最终一致性 |

**面试标准答法**：

> 我选择 Dubbo 的标准是：**这个调用失败后，整个业务操作是否必须终止，且没有降级空间？** 如果是——比如商家发布档期前的身份验证，失败了就不能继续——用 Dubbo，Triple 协议基于 HTTP/2 性能更好，强类型接口在编译期就能发现参数错误，异常也能直接透传无需解包。如果不是——比如列表页展示用户昵称，失败了可以显示"用户已注销"——继续用 OpenFeign，失败不影响主业务，且这类读操作未来可以用 Canal CDC 同步到本地缓存，彻底消除跨服务调用。

---

## 九、关键概念速查

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
| 接口级注册 | Dubbo 2 方式：每个接口单独注册，大规模场景有推送风暴风险 |
| 磁盘缓存 | Consumer 收到地址列表后写入本地文件，Nacos 宕机时作兜底 |
| 优雅下线 | 停机时 ShutdownHook 主动向 Nacos 注销，Consumer 立即感知 |
