# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

**档期预约平台** — Cosplay 社区预约平台，连接妆娘/摄影/毛娘等服务方（商家）与客人。

- **后端**：`BookSystem/`（Spring Boot 3.2 / Java 21 / MyBatis-Plus / Sa-Token）
- **前端**：`cosplay-frontend/`（Vue 3 + TypeScript + Pinia + Element Plus）
- **数据库名**：`mhp`（`resources/application.yaml`）
- **后端包路径**：`com.mhp.booksystem`
- **规格文档**：`学习路线与面试题.md`（完整技术设计 + 13 个开发阶段）

## 开发命令

```bash
# 中间件（MySQL 3306 / Redis 6379 / RabbitMQ 5672+15672 / Nacos 8848 / XXL-Job 8088 / ES 9200 / Kibana 5601 / Canal 11111）
# 首次运行需先构建含 IK 分词器的 ES 镜像（约 3~5 分钟）：
docker compose build elasticsearch
docker compose up -d

# 后端（BookSystem/ 目录下）
mvn clean package -DskipTests
java -jar target/app.jar
# 无自动化测试，构建始终加 -DskipTests

# 前端（cosplay-frontend/ 目录下）
npm run dev    # 开发服务器
npm run build  # 生产构建
```

**验证端点：** RabbitMQ 管理台 `localhost:15672`（admin/123456）、XXL-Job `localhost:8080/xxl-job-admin`、Swagger `localhost:8080/swagger-ui.html`

---

## 代码地图（快速定位）

> 后端根目录：`BookSystem/src/main/java/com/mhp/booksystem/`
> 前端根目录：`cosplay-frontend/src/`

### 后端关键文件

| 功能 | 文件（相对后端根目录） |
|------|----------------------|
| 统一响应 / 错误码 | `common/Result.java`、`common/ResultCode.java` |
| 业务异常 / 全局处理 | `common/exception/BusinessException.java`、`GlobalExceptionHandler.java` |
| **预约** Controller/Service/Impl | `controller/BookingController.java`、`service/BookingService.java`、`service/impl/BookingServiceImpl.java` |
| 档期 Controller/Service/Impl | `controller/ScheduleController.java`、`service/impl/ScheduleServiceImpl.java` |
| 商家 Controller/Service/Impl | `controller/MerchantController.java`、`service/impl/MerchantServiceImpl.java` |
| 消息 Controller/Service/Impl | `controller/MessageController.java`、`service/impl/MessageServiceImpl.java` |
| 实体类（11 个） | `entity/` — Booking、Schedule、Merchant、User、Post、Review、Complaint、Message、Follow、QuestionnaireTemplate、RushRecord |
| Mapper XML | `resources/mapper/MerchantMapper.xml`（商家搜索）、`MessageMapper.xml`（会话列表）、`FollowMapper.xml` |
| WebSocket 配置 | `config/WebSocketConfig.java` |
| STOMP 认证拦截器 | `websocket/StompAuthChannelInterceptor.java` |
| Sa-Token 路由拦截器 | `config/SaTokenConfig.java` |
| RabbitMQ 配置 | `config/RabbitConfig.java` |
| MQ 通知发送 / 消费 | `mq/MQSender.java`、`mq/NotifyConsumer.java`（消费后推 WebSocket） |
| MQ 消息体 | `mq/NotifyMessage.java` |
| 超时取消定时任务 | `job/BookingJobHandler.java`（XXL-Job 注册名：`cancelTimeoutBookingJob`） |
| 每日提醒定时任务 | `job/ReminderJobHandler.java`（注册名：`reminderJob`） |
| 抢档期 Lua 脚本 | `resources/lua/rush.lua` |
| 应用配置 | `resources/application.yaml` |

### 前端关键文件

| 功能 | 文件（相对前端 src/） |
|------|---------------------|
| API 全量封装 | `api/index.ts`（`bookingApi`、`merchantApi`、`scheduleApi` 等 11 个模块） |
| 类型定义 | `types/index.ts`（`BookingVO`、`BOOKING_STATUS_MAP`、`MerchantVO` 等） |
| Axios 封装 | `utils/request.ts`（请求拦截加 token Header，响应拦截解包 / 401 跳登录） |
| Pinia 用户 Store | `stores/user.ts`（`token`、`userInfo`、`isLoggedIn`；闲鱼模式，无 isMerchant 区分） |
| 路由 + 守卫 | `router/index.ts`（`requireAuth` + `role` meta 鉴权） |
| WebSocket 客户端 | `composables/useWebSocket.ts`（STOMP 全局单例，`unreadCount` 响应式未读数） |
| 公共头部 | `components/AppHeader.vue` |
| 预约列表页 | `views/BookingsView.vue`（客人"我的预约" + 商家"收到的预约"，同一组件 route name 区分） |
| 商家主页 | `views/MerchantDetailView.vue`（月历档期 + 预约/抢档期对话框 + 问卷渲染） |
| 消息聊天页 | `views/MessageView.vue` |

---

## 关键约定（必读）

### 命名：预约用 Booking，不用 Order

数据库表名是 `order`，但**所有代码**统一用 `Booking`，含：
- 后端：`BookingController`、`BookingService`、`BookingServiceImpl`、`BookingCreateDTO`、`BookingVO`、`BookingJobHandler`
- API 路径：`/api/booking`（不是 `/api/order`）
- Redis 锁 key：`booking:create:{userId}:{scheduleId}`
- MQ 方法：`sendBookingConfirmed / sendBookingCompleted / sendBookingCancelled`
- 前端：`bookingApi`、`BookingVO`、`BOOKING_STATUS_MAP`

### 预约状态流转

```
0（待确认）──confirm()──▶ 2（已定档）──complete()──▶ 3（已完成）
     │                                                      │
     └─────────────────cancel()─────────────────────────▶ 4（已取消）
```

状态 1（待付款）保留在数据库枚举中但**当前未使用**（无支付集成）。`confirm()` 直接设 status=2。

### 游标分页

所有列表（预约、消息、动态）用游标分页，不用 offset：
- 参数：`lastId`（上一页最后一条 id，首次传 null）
- 返回：`CursorPageVO<T>` = `{ list, hasMore, nextCursor }`

### 商家搜索（Elasticsearch）

搜索全走 ES，不走 MySQL：
- `keyword` 做 `multi_match` 搜索 `nickname^2`（权重×2）和 `intro`，IK 分词，比 MySQL LIKE 更精准且能搜到昵称
- `city` 做 `term` filter（精确匹配 keyword 字段）
- `serviceType` 做 `term` filter（integer 数组，匹配包含某个值）
- 有 keyword → 按相关度 `_score` 降序；无 keyword → 按 `avgScore` 降序

数据同步：**Canal CDC**
- MySQL binlog → Canal server（docker）→ `CanalSyncService`（mhp-account 内置客户端）→ ES
- 监听 `merchant` 和 `user` 两张表（user 改昵称时同步更新 ES doc 的 nickname/avatar）
- 全量初始化：`POST /internal/merchant/es/init`（首次部署调用一次）

ES 相关文件：
- `document/MerchantDoc.java` — ES 文档映射（@Document, IK analyzer）
- `repository/MerchantEsRepository.java` — ElasticsearchRepository 接口
- `canal/CanalSyncService.java` — Canal 客户端，ApplicationRunner 启动后台线程

---

## 关键实现模式

### Sa-Token 鉴权

token 通过 HTTP Header `token` 传递（不用 Cookie）。Sa-Token 路由白名单：`/api/user/login`、`/api/user/register`、`/ws/**`。Service 层用 `StpUtil.getLoginIdAsLong()` 取当前用户 id。

### 商家主页缓存（Cache Aside + 防三缓）

- **防穿透**：不存在的 id 写空字符串哨兵，TTL 2 分钟
- **防击穿**：Redisson 锁 `lock:merchant:{id}` + 双重检查
- **防雪崩**：TTL 随机 25~35 分钟
- **写策略**：更新 DB → `delete` 缓存 key（不直接更新缓存）

### Redisson 分布式锁

```java
RLock lock = redissonClient.getLock("booking:create:" + userId + ":" + scheduleId);
boolean locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
// finally 中：if (lock.isHeldByCurrentThread()) lock.unlock()
```

### 抢档期 Lua 原子脚本

`resources/lua/rush.lua`：ZSCORE 判重 → ZCARD 判满 → ZADD → ZRANK 返回名次。返回 -1=已在队列，0=队满，正整数=排队名次。

### WebSocket（STOMP over SockJS）

- 端点：`/ws`，SockJS 降级
- `StompAuthChannelInterceptor` 拦截 CONNECT 命令，从 native header 取 `token`，用 `StpUtil.getLoginIdByToken()` 验证后 `setUser()`
- `NotifyConsumer` 消费 MQ 通知后调用 `messagingTemplate.convertAndSendToUser()` 推送到 `/queue/notify`
- `MessageServiceImpl` 保存消息后推送到 `/queue/messages`
- 前端订阅：`/user/queue/messages`（聊天）、`/user/queue/notify`（系统通知）

### RabbitMQ

- 交换机：`schedule.exchange`（Topic）；死信：`schedule.dead.exchange`（Direct）
- 消费者幂等：`SET NX "msg:processed:{msgId}" 1 EX 86400`（Redis 去重）

### ResultCode 错误码分段

`2xx` 通用 / `1xxxx` 档期 / `2xxxx` 预约（`BOOKING_NOT_FOUND=20002`、`BOOKING_DUPLICATE=20001`、`BOOKING_STATUS_ERROR=20003`）/ `3xxxx` 用户

---

## 微服务架构（BookSystem/）

### 模块结构

```
BookSystem/
├── mhp-common      公共模块（Result、ResultCode、BusinessException、CursorPageVO、Feign DTO/Client）
├── mhp-gateway     API 网关，端口 80
├── mhp-account     账号服务（User + Merchant），端口 8081
├── mhp-booking     预约服务（Schedule + Booking + Rush + Questionnaire + XXL-Job），端口 8082
└── mhp-social      社区服务（Post + Follow + Review + Complaint + Message + WebSocket + 七牛云），端口 8083
```

OpenFeign 调用方向（单向无环）：
- mhp-booking → mhp-account（验证商家身份、获取用户信息）
- mhp-social → mhp-account（获取用户/商家展示信息）
- mhp-social → mhp-booking（Review/Complaint 校验预约状态）

MQ 流向：mhp-booking（MQSender）→ RabbitMQ → mhp-social（NotifyConsumer → WebSocket push）

内部接口（不经过 Gateway）：
- mhp-account: `GET /internal/user/{id}`, `GET /internal/user/batch`, `GET /internal/merchant/{id}`, `GET /internal/merchant/by-user/{userId}`, `GET /internal/merchant/batch`, `PUT /internal/merchant/{id}/score`
- mhp-booking: `GET /internal/booking/{id}`

### 请求链路

**开发环境：**
```
前端 Vite dev server（5173）
  → proxy /api/* → Gateway:80
    → /api/user/**, /api/merchant/**     → lb://mhp-account:8081
    → /api/schedule/**, /api/booking/**, /api/questionnaire/** → lb://mhp-booking:8082
    → /api/post/**, /api/follow/**, /api/review/**, ...        → lb://mhp-social:8083
    → /ws/**                             → lb://mhp-social:8083
```

**生产环境：**
```
用户请求
  → Nginx（443/80）
      ├── location /          → 直接返回 dist/ 静态文件（index.html / JS / CSS）
      └── location /api/      → proxy_pass Gateway:80
            → 按路径分发到对应微服务
```

Nginx 处理前端静态文件和 SSL，不需要经过 Gateway；只有 `/api/` 请求才进入微服务链路。

### Nginx 与 Gateway 的分工

| | Nginx | Spring Cloud Gateway |
|---|---|---|
| 角色 | 基础设施层反向代理 | 应用层智能路由 |
| 路由目标 | 写死 IP:Port | 服务名（`lb://mhp-app`），自动服务发现 |
| 新增实例 | 需改配置 + reload | Nacos 自动感知，无需改动 |
| 自定义逻辑 | Lua 脚本 | Java Filter（鉴权、限流、日志） |
| 擅长 | SSL、静态文件、高吞吐 | 服务路由、业务过滤器 |

### Nacos（localhost:8080 控制台 / 8848 API）

- **服务注册**：mhp-app 启动后自动注册，Gateway 通过 `lb://mhp-app` 解析实例列表
- **配置中心**：Data ID `mhp-app.yaml`，Group `DEFAULT_GROUP`；`spring.config.import: nacos:mhp-app.yaml` 启动时拉取，`@RefreshScope` 支持热更新

### 开发命令（微服务）

```bash
# 在 BookSystem/ 目录下构建所有模块
mvn clean package -DskipTests

# 先启动 Nacos，再按顺序启动：
java -jar mhp-app/target/mhp-app-1.0.0.jar      # 注册到 Nacos
java -jar mhp-gateway/target/mhp-gateway-1.0.0.jar  # 从 Nacos 获取路由目标
```

---

## 当前进度（截至 2026-08-21）

所有业务功能均已实现（11 个后端 Controller、11 个前端页面）。

微服务改造进度：

| 步骤 | 内容 | 状态 |
|------|------|------|
| 1 | Maven 多模块拆分 | ✅ |
| 2 | Nacos 服务注册 | ✅ |
| 3 | Nacos 配置中心 | ✅ |
| 4 | Gateway 路由（mhp-app → 3 个微服务） | ✅ |
| 5 | 热更新验证（@RefreshScope） | ⏳ |
| 6 | 服务拆分 + OpenFeign 跨服务调用 | ✅ |

**待完成：**
- 七牛云 CDN 域名（`application.yaml` 中 `domain: cdn.your-domain.com` 为占位符）
- Sentinel 控制台限流规则（`@SentinelResource` 注解已加，QPS 阈值待配置）
- 生产部署（Nginx + SSL certbot + ICP 备案）
- 管理员后台（投诉处理，未规划）

---

## Maven 多模块与 Java 包机制（常见疑问）

### Java 包名是怎么来的？

包名 = `src/main/java/` 之后的目录路径，把 `/` 换成 `.`。

```
mhp-common/src/main/java/com/mhp/booksystem/feign/AccountFeignClient.java
                          ↑ 这段路径
→ 包名：com.mhp.booksystem.feign
→ 全限定类名：com.mhp.booksystem.feign.AccountFeignClient
```

所以 `@EnableFeignClients(basePackages = "com.mhp.booksystem.feign")` 扫描的是这个路径，它能找到 mhp-common 里的接口，因为 mhp-common 已作为 Maven 依赖引入，其类在编译后与当前模块一起出现在 classpath 上。

### 不同模块可以用相同的包名吗？

**可以，Maven 多模块项目里这很常见。**

本项目中 mhp-common、mhp-account、mhp-booking、mhp-social 都用同一个包根 `com.mhp.booksystem`，这完全没有问题。

根本原因：JVM 的 classpath 是**扁平的**。编译/运行时，所有 jar 里的 `.class` 文件被展开放到同一个查找空间，JVM 按全限定类名（包名 + 类名）查找类，不关心这个类来自哪个 jar。所以"不同模块、相同包名"在 JVM 眼里根本不是分离的，它们从一开始就在同一命名空间。

```
classpath（运行时）：
  mhp-common.jar  ──┐
  mhp-account.jar ──┤──▶ JVM 统一查找：com.mhp.booksystem.feign.AccountFeignClient
  mhp-booking.jar ──┤                  com.mhp.booksystem.service.BookingService
  ...             ──┘                  ...
```

### 唯一要避免的：类名冲突

如果两个 jar 里都有 `com.mhp.booksystem.foo.Bar`（包名 + 类名完全相同），JVM 会取 classpath 上**先出现**的那个，另一个永远不会被加载。这是隐蔽 bug 的来源，构建时不会报错，运行时行为不符合预期。**本项目不同模块的类名没有重叠，不存在此风险。**

### JPMS（Java 模块系统）的限制

Java 9 引入的 JPMS（`module-info.java`）明确**禁止** Split Package — 即同一包名的类散落在不同模块里。原因是 JPMS 要求每个包只属于一个模块，以保证强封装边界清晰。

本项目**不使用 JPMS**（没有 `module-info.java`），走的是传统 classpath 模式，所以共享包名完全合规，不受此限制。现阶段大多数 Spring Boot 项目也都不用 JPMS。

---

## Spring Boot 启动与请求完整生命周期

### 一、自动配置原理（Auto-configuration）

Spring Boot 的核心魔法：**只需引入依赖，框架自动帮你配好相关 Bean，不需要手写 XML 或 @Bean 方法。**

实现机制：
1. `@SpringBootApplication` 内含 `@EnableAutoConfiguration`。
2. 启动时扫描 classpath 上所有 jar 里的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Spring Boot 3.x；旧版用 `spring.factories`）。
3. 该文件列出数百个自动配置类（如 `RedisAutoConfiguration`、`RabbitAutoConfiguration`）。
4. 每个自动配置类头上挂 `@ConditionalOn...` 注解，按条件决定是否生效：
   - `@ConditionalOnClass(RedisTemplate.class)` — classpath 有这个类才生效（即引入了 redis starter）
   - `@ConditionalOnMissingBean(RedisTemplate.class)` — 你没有自己定义才生效（尊重用户配置优先）
   - `@ConditionalOnProperty("spring.redis.host")` — 配置文件有这个 key 才生效

**示例**：引入 `spring-boot-starter-data-redis` → classpath 出现 `RedisTemplate` → `RedisAutoConfiguration` 条件满足 → 自动创建 `RedisTemplate` Bean → 你可以直接 `@Autowired RedisTemplate`，无需任何配置代码。

---

### 二、启动阶段：哪些组件被扫描和注册

`@SpringBootApplication` = `@Configuration` + `@ComponentScan` + `@EnableAutoConfiguration`

`@ComponentScan` 扫描**启动类所在包及其所有子包**，识别以下注解并注册为 Bean：

| 注解 | 用途 | Spring 如何使用它 |
|------|------|------------------|
| `@Configuration` | 配置类 | 调用其中所有 `@Bean` 方法；若实现 `WebMvcConfigurer`，调用其钩子方法组装 MVC 配置 |
| `@Component` / `@Service` / `@Repository` | 普通 Bean / 服务层 / 数据层 | 注册为 Bean，供构造器注入 / `@Autowired` 使用 |
| `@Controller` / `@RestController` | 处理 HTTP 请求 | 注册到 DispatcherServlet 的路径→方法映射表 |
| `@RestControllerAdvice` | 全局异常处理 | `ExceptionHandlerExceptionResolver` 扫描后建立"异常类型→处理方法"映射表 |
| `@Aspect` | AOP 切面 | Spring AOP 为目标类创建代理，在切入点前后插入增强逻辑 |

`WebMvcConfigurer` 实现类（如 `SaTokenConfig`）被扫描为 Bean 后，Spring 在初始化 MVC 时依次回调其钩子方法：
- `addInterceptors(registry)` — 注册拦截器（本项目：注册 SaInterceptor 做登录校验）
- `addCorsMappings(registry)` — 配置跨域
- `addResourceHandlers(registry)` — 静态资源映射
- ……等约 20 个扩展点

> **为什么是 "扫描到就自动生效"？** 因为 Spring 在初始化时会遍历所有 WebMvcConfigurer Bean，调用它们的钩子方法。你只需实现接口 + 加 `@Configuration`，框架自动"来问你要配置"，不需要你主动触发。

---

### 三、一次请求从到达到返回的完整流程

以"发起预约"（`POST /api/booking`，需要登录）为例：

```
[浏览器] POST /api/booking  Header: token=xxx  Body: JSON

    ↓
[Tomcat 线程池] 分配一条线程（线程生命周期 = 本次请求全程）

    ↓
[DispatcherServlet] Spring MVC 的总前端控制器，所有 HTTP 请求的唯一入口

    ↓ ── 拦截器链 preHandle 阶段 ───────────────────────────────────────────────
[SaInterceptor.preHandle()]
  1. 从 Header 取 token 字符串
  2. 用 token 查 Redis（token→userId 记录）
       ├─ 不存在 / 已过期 → 抛 NotLoginException → 跳到"异常路径"
       └─ 存在且有效     → 将 userId 写入当前线程的 ThreadLocal
  3. 白名单路径（/login /register /internal/**）→ 跳过第2步，直接放行

    ↓ ── Handler 映射 ──────────────────────────────────────────────────────────
根据 URL + HTTP Method 在映射表里找到目标 Controller 方法
（映射表在启动时由 @ComponentScan 扫描 @RestController 建立）

    ↓ ── 参数绑定 + Bean Validation ────────────────────────────────────────────
Spring 将请求 Body（JSON）反序列化为 BookingCreateDTO
若 Controller 参数标了 @Valid，触发 Hibernate Validator 校验 DTO 上的所有注解：
  ├─ 校验通过 → 进入 Controller 方法体
  └─ 校验失败 → 抛 MethodArgumentNotValidException → 跳到"异常路径"（不进方法体）

    ↓ ── Controller 方法体 ─────────────────────────────────────────────────────
BookingController.create(dto):
  调用 BookingService.create(dto)
  Service 内 StpUtil.getLoginIdAsLong() 从 ThreadLocal 读 userId（O(1)，无 Redis 查询）
  ├─ 正常完成 → 返回 Result.ok(data)
  └─ 业务异常 → throw new BusinessException(ResultCode.BOOKING_DUPLICATE)

    ↓ ─────────── 正常路径 ──────────────  异常路径 ──────────────────────────────
                                         ↓
                          [异常向上冒泡至 DispatcherServlet 的 try-catch]
                          BusinessException / MethodArgumentNotValidException
                          / NotLoginException / 其他 RuntimeException
                                         ↓
                          [ExceptionHandlerExceptionResolver]
                          查"异常类型→处理方法"映射表
                          → 找到 GlobalExceptionHandler 中对应的 @ExceptionHandler 方法
                          → 调用该方法，得到 Result.error(...) 对象
                                         ↓
[返回值序列化] ←─────────────────────────┘
  @ResponseBody 将 Result 对象序列化为 JSON，写入 HTTP 响应体

    ↓ ── 拦截器链 afterCompletion 阶段 ────────────────────────────────────────
Sa-Token 清除当前线程的 ThreadLocal（无论正常还是异常都会执行）
防止线程归还到池后，数据残留污染下一个请求

    ↓
[Tomcat] 线程归还到池，等待下一个请求

    ↓
[浏览器] 收到 HTTP 响应
```

---

### 四、关键概念对照表

| 概念 | 发生时机 | 作用 |
|------|---------|------|
| `@Configuration` + `WebMvcConfigurer` | 启动期 | Spring 回调 `addInterceptors()` 等方法，注册拦截器和 MVC 扩展配置 |
| `@RestControllerAdvice` | 启动期扫描，请求期使用 | 全局异常兜底，统一错误响应格式 |
| `SaInterceptor.preHandle()` | 请求到达，进 Controller 前 | 验证 token，将 userId 写入 ThreadLocal |
| `@Valid` + Hibernate Validator | 进 Controller 前（参数绑定后） | 校验 DTO 字段，失败直接返回 400，不进方法体 |
| `StpUtil.getLoginIdAsLong()` | Controller / Service 中 | 从 ThreadLocal 读当前用户 id，O(1) 无 IO |
| `BusinessException` | Service 中 `throw` | 携带 ResultCode，被 GlobalExceptionHandler 捕获转为统一错误响应 |
| `afterCompletion()` | Controller 返回后 | ThreadLocal 清理，防内存泄漏和数据污染 |
| Tomcat 线程 | 贯穿整个请求 | 1请求=1线程，ThreadLocal 与之同生共死；同一用户的不同请求使用不同线程 |

---

## 代码阅读顺序指南

### 第一层：地基（无依赖，读懂后所有代码都清晰）

**`mhp-common/`** — 先把这里读完

1. `common/Result.java` + `ResultCode.java` — 所有接口的响应格式和错误码分段规则
2. `common/exception/BusinessException.java` + `GlobalExceptionHandler.java` — 业务异常如何抛出和统一捕获
3. `vo/CursorPageVO.java` — 游标分页的数据结构（为什么不用 offset）
4. `dto/feign/` 下四个 DTO — 服务间传递的数据长什么样
5. `feign/AccountFeignClient.java` + `BookingFeignClient.java` — 哪些跨服务调用、调用方向

---

### 第二层：数据库结构（建立字段印象）

直接看实体类，不用看建表 SQL，注释已说明设计原因：

- `mhp-account`: `User.java` → `Merchant.java`
- `mhp-booking`: `Schedule.java` → `Booking.java` → `RushRecord.java` → `QuestionnaireTemplate.java`
- `mhp-social`: `Post.java` → `Follow.java` → `Review.java` → `Message.java` → `Complaint.java`

---

### 第三层：核心业务（按复杂度递增）

**mhp-account** — 最简单，先读

1. `UserServiceImpl` — 注册/登录（MD5 + Sa-Token 写 Redis）
2. `MerchantServiceImpl` — 重点看 `getDetail()`，三缓防护 + Redisson 双重检查完整实现

**mhp-booking** — 核心模块

1. `ScheduleServiceImpl.create()` / `listByMonth()` — 档期基本流程
2. `BookingServiceImpl.create()` — Redisson 锁 + 双重检查 + 重复预约校验
3. `BookingServiceImpl.confirm/complete/cancel()` — 状态流转 + MQ 通知
4. `ScheduleServiceImpl.rush()` — Lua 脚本原子抢档期，配合 `resources/lua/rush.lua` 一起看
5. `BookingJobHandler` + `ReminderJobHandler` — XXL-Job 定时任务

**mhp-social** — 依赖前两个服务最多

1. `ReviewServiceImpl.create()` — 跨服务校验（BookingFeign）+ 更新商家评分（AccountFeign）
2. `MessageServiceImpl` — 发消息 + WebSocket 实时推送
3. `PostServiceImpl.toggleLike()` — Redis 点赞去重
4. `MQSender`（booking 侧）+ `NotifyConsumer`（social 侧）— 对照看 MQ 消息流向

---

### 第四层：基础设施（理解"为什么能跑起来"）

- `config/SaTokenConfig.java`（任意一个服务）— 白名单拦截器原理
- `config/MyMetaObjectHandler.java` — createTime/updateTime 自动填充
- `websocket/StompAuthChannelInterceptor.java` — WS 握手时如何验 token、为什么在这里而不在 HTTP 拦截器
- `config/WebSocketConfig.java` — STOMP 端点、消息代理、用户目的地前缀
- `config/RabbitConfig.java` — 交换机/队列/死信队列绑定关系
- `mhp-gateway/application.yml` — 路由规则（看完整个请求链路就通了）

---

### 第五层：前端（从外向内）

1. `utils/request.ts` — Axios 封装（token 注入 + 401 跳转 + 响应解包）
2. `router/index.ts` — 路由懒加载 + 导航守卫
3. `stores/user.ts` — 登录态持久化（内存 + localStorage 双写）
4. `api/index.ts` — 所有接口一览（和后端 Controller 路径对照）
5. `composables/useWebSocket.ts` — STOMP 全局单例 + 订阅 + 未读数响应式
6. 页面：`LoginView` → `HomeView` → `MerchantDetailView`（最复杂）→ `BookingsView` → `MessageView`

---

### 一条贯穿全栈的主线

跑通这个场景，基本全懂了：

```
用户登录（UserService + Sa-Token + Redis）
  → 搜索商家（MerchantMapper XML + JSON_CONTAINS）
  → 查看主页（getDetail 三缓防护 + Redisson）
  → 发起预约（BookingService + Redisson 分布式锁）
  → 商家确认（状态流转 → MQSender → RabbitMQ）
  → WebSocket 推通知给客人（NotifyConsumer → SimpMessagingTemplate）
  → 客人评价（ReviewService + BookingFeign 跨服务校验 + 更新商家评分）
```
