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
# 中间件（MySQL 3306 / Redis 6379 / RabbitMQ 5672+15672 / XXL-Job 8080）
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
| Pinia 用户 Store | `stores/user.ts`（`token`、`userInfo`、`isLoggedIn`、`isMerchant`） |
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

### 商家搜索

`MerchantMapper.xml` 使用 `JSON_CONTAINS(service_types, CAST(#{serviceType} AS JSON))` 搜索 JSON 数组字段，无需 Elasticsearch。

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
├── mhp-common      公共模块（Result、ResultCode、BusinessException、CursorPageVO）
├── mhp-gateway     API 网关，端口 8000
└── mhp-app         业务单体（后续拆分为多个微服务），端口 8081
```

### 请求链路

**开发环境：**
```
前端 Vite dev server（5173）
  → proxy /api/* → Gateway:8000
    → lb://mhp-app → mhp-app:8081
```

**生产环境：**
```
用户请求
  → Nginx（443/80）
      ├── location /          → 直接返回 dist/ 静态文件（index.html / JS / CSS）
      └── location /api/      → proxy_pass Gateway:8000
            → lb://mhp-app  → mhp-app:8081（多实例时自动负载均衡）
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
| 4 | Gateway 路由（8000 → mhp-app:8081） | ✅ |
| 5 | 热更新验证（@RefreshScope） | ⏳ |
| 6 | 服务拆分 + OpenFeign 跨服务调用 | ⏳ |

**待完成：**
- 七牛云 CDN 域名（`application.yaml` 中 `domain: cdn.your-domain.com` 为占位符）
- Sentinel 控制台限流规则（`@SentinelResource` 注解已加，QPS 阈值待配置）
- 生产部署（Nginx + SSL certbot + ICP 备案）
- 管理员后台（投诉处理，未规划）
