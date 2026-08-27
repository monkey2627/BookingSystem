# Dubbo 系统笔记（面试版）

---

## 一、RPC 整体开发模型

### 什么是 RPC？

RPC（Remote Procedure Call，远程过程调用）的目标：**让调用远程服务像调用本地方法一样**，开发者不用关心网络细节。

### Dubbo 的六个核心角色

```
Registry（注册中心，Nacos）
   ↑ 注册          ↑ 订阅/推送
Provider ──────────────────── Consumer
（服务提供方）    网络调用     （服务消费方）
```

| 角色 | 职责 |
|------|------|
| **Provider** | 暴露服务，启动时向注册中心注册自己的地址和接口信息 |
| **Consumer** | 调用服务，启动时从注册中心订阅自己需要的服务地址列表 |
| **Registry** | 服务发现中心（本项目用 Nacos），维护 Provider 地址列表，实时推送变更 |
| **Proxy（代理层）** | Consumer 拿到的不是真实实现，而是框架自动生成的动态代理；代理内部完成序列化→网络传输→反序列化 |
| **Serialization（序列化）** | 把方法名/参数/返回值序列化成字节流（Dubbo 默认 Hessian2，Dubbo3 Triple 也支持 Protobuf） |
| **Transport（传输层）** | 底层网络通信，Dubbo 默认 Netty；Triple 协议基于 HTTP/2 |

### 一次 RPC 调用的完整流程

```
Consumer 调用接口方法
    ↓
动态代理拦截
    ↓
序列化（方法名 + 参数 → 字节流）
    ↓
从注册中心拿到的地址列表中，按负载均衡策略选一个 Provider
    ↓
Netty 发送请求（TCP/HTTP/2）
    ↓
Provider 接收 → 反序列化 → 反射调用真实实现
    ↓
结果序列化 → 网络返回
    ↓
Consumer 代理反序列化 → 返回给调用方
```

---

## 二、核心注解 + 项目示例

### 2.1 Provider 端（服务提供方）

```java
// 1. 在 mhp-common 定义接口（Provider/Consumer 共同依赖）
public interface RpcMerchantService {
    MerchantDTO getMerchantByUserId(Long userId);
    void updateMerchantScore(Long merchantId, MerchantScoreUpdateDTO dto);
}

// 2. 在 mhp-account 实现接口，@DubboService 暴露为 Dubbo 服务
@DubboService(version = "1.0.0")      // ← Dubbo 注解，不是 Spring @Service
@RequiredArgsConstructor
public class RpcMerchantServiceImpl implements RpcMerchantService {

    private final MerchantService merchantService;

    @Override
    public MerchantDTO getMerchantByUserId(Long userId) {
        // 查 DB，转换为 DTO 返回
    }
}
```

### 2.2 Consumer 端（服务消费方）

```java
// @DubboReference 注入 Dubbo 代理对象，用法与 @Autowired 类似
// 注意：必须是非 final 字段（不能用 @RequiredArgsConstructor 注入）
@Service
@RequiredArgsConstructor
public class BookingServiceImpl {

    private final AccountFeignClient accountFeignClient;   // 普通 Feign（final）

    @DubboReference(version = "1.0.0")                    // Dubbo RPC（非 final）
    private RpcMerchantService rpcMerchantService;

    public void someMethod() {
        // 直接调用，和本地方法完全一样的写法
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
    }
}
```

### 2.3 启动类

```java
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.mhp.booksystem")  // 扫描 @DubboService
public class AccountApplication { ... }
```

### 2.4 application.yaml 配置

```yaml
dubbo:
  application:
    name: mhp-account           # 服务名，注册到 Nacos 时的标识
  protocol:
    name: tri                   # Triple 协议（HTTP/2）
    port: -1                    # -1 = 随机端口，避免与 Spring Boot HTTP 冲突
  registry:
    address: nacos://127.0.0.1:8848   # 复用已有 Nacos，不引入新中间件
```

---

## 三、注册中心的角色（以 Nacos 为例）

### 为什么需要注册中心？

Consumer 不能把 Provider 的 IP 写死在配置里——Provider 可能有多台（横向扩展），也可能宕机（需要摘除）。注册中心解决**动态服务发现**问题。

### 完整注册/发现流程

```
1. Provider 启动
   → 向 Nacos 注册：服务名=mhp-account，协议=tri，ip=192.168.1.10，port=20880
   → Nacos 记录这条地址

2. Consumer 启动
   → 向 Nacos 订阅：我需要 mhp-account 的所有地址
   → Nacos 返回地址列表并建立长连接推送

3. 运行中
   → 有新 Provider 上线 → Nacos 推送给所有 Consumer
   → Provider 宕机 → Nacos 心跳超时 → 摘除该地址 → 推送给 Consumer

4. Consumer 本地缓存地址列表
   → 注册中心短暂不可用时，Consumer 仍可正常调用（降级容灾）
```

### 本项目的复用策略

Dubbo 注册中心直接复用 Spring Cloud 已有的 Nacos（`nacos://127.0.0.1:8848`），不引入额外中间件。Nacos 上会同时看到：
- Spring Cloud 的服务注册（HTTP REST，用于 OpenFeign）
- Dubbo 的服务注册（tri:// 协议，用于 RPC）

---

## 四、负载均衡策略

Consumer 拿到 Provider 地址列表后，按策略选择一个发请求。

| 策略 | 算法 | 适用场景 |
|------|------|---------|
| `random`（默认） | 按权重随机 | 通用，机器性能相同时最均衡 |
| `roundrobin` | 加权轮询 | 通用，可设置每台权重不同 |
| `leastactive` | 最少活跃数（正在处理的请求数最少的优先） | 机器性能差异大时，让强机多干 |
| `consistenthash` | 一致性哈希（相同参数总是打到同一台） | 需要会话粘性，如缓存场景 |
| `shortestresponse` | 最短响应时间优先 | 对延迟敏感 |

```java
// 配置方式：Consumer 侧
@DubboReference(version = "1.0.0", loadbalance = "leastactive")
private RpcMerchantService rpcMerchantService;
```

---

## 五、集群容错策略

调用失败时，Dubbo 如何处理？

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| `failover`（默认） | 失败自动切换到另一台重试（默认重试 2 次） | 读操作（查询），幂等接口 |
| `failfast` | 失败立即报错，不重试 | 写操作（创建、支付），非幂等接口 |
| `failsafe` | 失败忽略，不抛异常 | 日志上报、审计等次要操作 |
| `failback` | 失败后异步定期重试 | 最终一致性场景（消息补偿） |
| `forking` | 并发调用多台，只要有一台成功就返回 | 对延迟极敏感，可以接受资源浪费 |
| `broadcast` | 广播所有 Provider，有一台失败即报错 | 刷新所有节点缓存 |

```java
// 本项目写操作用 failfast，防止重试导致重复写入
@DubboReference(version = "1.0.0", cluster = "failfast")
private RpcMerchantService rpcMerchantService;
```

**面试要点**：`getMerchantByUserId` 是幂等查询 → 用 `failover`（默认）。`updateMerchantScore` 是写操作 → 若担心重复写，改 `failfast`。

---

## 六、SPI 扩展机制

### 什么是 SPI？

SPI（Service Provider Interface）是**接口与实现解耦**的标准机制。Dubbo 大量使用自己的增强版 SPI，允许你用一行配置替换框架内任何组件。

### Dubbo SPI vs Java SPI

| 对比项 | Java SPI | Dubbo SPI |
|--------|---------|-----------|
| 加载方式 | 全部加载，不管用不用 | 按名字按需加载（懒加载） |
| 扩展点 | 不支持 IoC/AOP | 支持 IoC 注入、AOP 包装（Wrapper） |
| 自适应扩展 | 不支持 | `@Adaptive`：根据 URL 参数动态选择实现 |

### 如何自定义扩展

```java
// 1. 实现扩展接口
public class MyLoadBalance implements LoadBalance {
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 自定义选择逻辑
        return invokers.get(0);
    }
}

// 2. 在 META-INF/dubbo/ 下创建文件 org.apache.dubbo.rpc.cluster.LoadBalance
// 文件内容：
myLB=com.example.MyLoadBalance

// 3. 使用
@DubboReference(loadbalance = "myLB")
```

### 实际意义（面试怎么答）

> "Dubbo SPI 让框架的每个核心点都是可替换的插件：序列化、负载均衡、集群容错、协议、注册中心…… 这是 Dubbo 能支持那么多生态的核心原因。我在项目里没有自定义 SPI，但理解它的工作原理很重要——面试中 Dubbo 的扩展性问题基本都落在 SPI 上。"

---

## 七、超时、重试、版本、分组

### 7.1 超时（timeout）

```java
// Provider 侧：最长允许执行时间（超过则向 Consumer 返回超时异常）
@DubboService(version = "1.0.0", timeout = 3000)   // 单位 ms

// Consumer 侧：等待 Provider 响应的最长时间（Consumer 优先级更高）
@DubboReference(version = "1.0.0", timeout = 2000)
```

**原则**：Consumer 配置优先于 Provider；接口级优先于方法级。

### 7.2 重试（retries）

```java
@DubboReference(version = "1.0.0", retries = 2)   // 失败后最多重试 2 次（不算第一次）
```

**注意**：默认集群容错是 `failover`（自动重试），`retries=0` 即等价于 `failfast`。**写操作必须设 `retries=0`，否则可能重复执行。**

### 7.3 版本（version）

```java
// Provider A（旧版本）
@DubboService(version = "1.0.0")
public class RpcMerchantServiceImplV1 implements RpcMerchantService { ... }

// Provider B（新版本，灰度发布）
@DubboService(version = "2.0.0")
public class RpcMerchantServiceImplV2 implements RpcMerchantService { ... }

// Consumer 精确指定版本
@DubboReference(version = "1.0.0")   // 只调旧版
@DubboReference(version = "2.0.0")   // 只调新版
@DubboReference(version = "*")       // 随机调任意版本（灰度测试）
```

**用途**：灰度发布、接口不兼容升级时的平滑过渡。

### 7.4 分组（group）

```java
// 同一接口的不同业务实现
@DubboService(group = "vip")
public class VipMerchantServiceImpl implements RpcMerchantService { ... }

@DubboService(group = "normal")
public class NormalMerchantServiceImpl implements RpcMerchantService { ... }

// Consumer 按业务选择
@DubboReference(group = "vip")
```

**用途**：同一接口按业务场景路由到不同实现（如 VIP 用户走专属服务）。

---

## 八、Dubbo RPC vs OpenFeign HTTP

| 对比维度 | Dubbo3 Triple（RPC） | OpenFeign（HTTP REST） |
|---------|---------------------|----------------------|
| **协议** | HTTP/2（Triple）/ TCP（Dubbo协议） | HTTP/1.1 |
| **序列化** | Hessian2 / Protobuf（二进制，紧凑） | JSON（文本，可读性好） |
| **性能** | 更高（二进制+多路复用+Header压缩） | 相对低（文本+每个请求独立连接） |
| **接口契约** | 强类型 Java 接口，编译期检查 | HTTP URL + JSON，运行时发现错误 |
| **错误传播** | 异常直接透传到 Consumer | 需要解包 Result，手动判断 code |
| **适用场景** | 服务间强业务依赖调用（失败=业务失败） | 展示数据补充、跨语言、外部接口 |
| **跨语言** | Triple 与 gRPC 互通（支持 Protobuf） | 天然跨语言（任何语言都能发 HTTP） |
| **注册中心** | 必须（服务发现） | 可选（也支持硬编码 URL） |

### 本项目的分工原则

**选 Dubbo（强业务依赖）的 3 个调用：**

| 调用 | 调用方 | 理由 |
|------|--------|------|
| `getMerchantByUserId` | mhp-booking | 创建档期/预约的商家身份验证，失败则整个写操作失败 |
| `updateMerchantScore` | mhp-social | 唯一的跨服务写操作，强一致性要求 |
| `getBookingById` | mhp-social | 评价/投诉的前置校验，失败则拒绝业务 |

**保留 Feign（弱业务依赖）的 4 个调用：**

| 调用 | 调用方 | 理由 |
|------|--------|------|
| `batchGetUsers` | booking / social | 展示数据（头像昵称），失败可降级显示占位符 |
| `batchGetMerchants` | booking / social | 展示数据，未来可用 Canal CDC 缓存消除调用 |
| `getMerchant` | social 投诉流程 | 查辅助信息，非核心校验，失败可降级 |
| `getUser` | social 消息流程 | 存在性检查，最终一致性可接受 |

### 面试标准答法

> "我选择 Dubbo 的标准是：**这个调用失败后，整个业务操作是否必须终止，且没有降级空间？** 如果是——比如商家发布档期前的身份验证，失败了就不能继续，这是强前置条件——用 Dubbo，因为 Triple 协议基于 HTTP/2，比 HTTP/1.1 多路复用性能更好，强类型接口在编译期就能发现参数错误。如果不是——比如列表页展示用户昵称，失败了可以显示'用户已注销'——继续用 OpenFeign，失败不影响主业务，且这类读操作未来可以用 Canal CDC 同步到本地缓存彻底消除跨服务调用。"

---

## 九、Dubbo3 Triple 协议

### 为什么推出 Triple？

Dubbo 老协议（dubbo://）是私有 TCP 协议，在云原生场景有两个问题：
1. 不能穿透 HTTP 网关（Nginx、Envoy 认识 HTTP，不认识私有 TCP）
2. 不能和 gRPC 生态互通

Triple 协议解决这两个问题。

### Triple 的技术特性

| 特性 | 说明 |
|------|------|
| **基于 HTTP/2** | 多路复用（一个连接并发多个请求）+ Header 压缩（HPACK）+ 二进制帧 |
| **与 gRPC 互通** | 遵循 gRPC 协议规范，可以被 gRPC 客户端直接调用 |
| **穿透 HTTP 网关** | HTTP/2 可通过 Nginx/Envoy 转发，适合 Service Mesh / Istio |
| **支持 Protobuf** | 可选用 Protobuf 序列化（比 Hessian2 更紧凑，跨语言更友好） |
| **向下兼容** | Dubbo3 服务既能被 Triple 客户端调用，也能被老 Dubbo 协议客户端调用 |

### HTTP/2 vs HTTP/1.1 的核心差异

```
HTTP/1.1：每个请求独占一个 TCP 连接（或串行复用），有队头阻塞问题
           请求1 ──▶ 等响应1 ──▶ 请求2 ──▶ 等响应2...

HTTP/2：  一个连接内，多个请求并发（多路复用），互不阻塞
           请求1 ──▶
           请求2 ──▶  ──▶ 响应3
           请求3 ──▶  ──▶ 响应1
                       ──▶ 响应2
```

### 本项目的配置

```yaml
dubbo:
  protocol:
    name: tri   # 启用 Triple 协议
    port: -1    # 随机端口（避免与 Spring Boot HTTP 端口冲突）
```

`port: -1` 的原理：Dubbo 在启动时找一个可用端口绑定，然后把实际端口注册到 Nacos。Consumer 从 Nacos 拿到的地址里包含真实端口，不需要手动配置。

---

## 十、关键概念速查

| 概念 | 一句话说明 |
|------|-----------|
| `@DubboService` | Provider 侧注解，将实现类发布为 Dubbo 服务（等价于 `@Service` + 服务注册） |
| `@DubboReference` | Consumer 侧注解，注入 Dubbo 动态代理（等价于 `@Autowired` 但走 RPC） |
| `@EnableDubbo` | 启动类注解，开启 Dubbo 自动配置 + `@DubboService` 扫描 |
| `version` | 服务版本，Consumer 和 Provider 必须一致才能匹配 |
| `group` | 服务分组，同一接口多个实现时按业务路由 |
| `timeout` | 超时时间（ms），Consumer 侧配置优先 |
| `retries` | 重试次数，写操作设 0 防重复执行 |
| Triple | Dubbo3 新协议，HTTP/2 + gRPC 互通 + 穿透网关 |
| Hessian2 | Dubbo 默认序列化协议（二进制），要求 DTO 实现 `Serializable` |
| SPI | Dubbo 插件化核心机制，框架所有组件均可按名替换 |
| `failover` | 默认集群容错：失败自动切换重试（只用于幂等调用） |
| `failfast` | 快速失败，不重试（写操作必用） |
