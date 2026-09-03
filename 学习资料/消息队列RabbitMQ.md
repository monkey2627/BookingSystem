# RabbitMQ 从入门到深入

---

## 一、为什么需要消息队列

### 三大核心作用

**1. 异步（Async）**

同步调用：A 调 B → 等 B 执行完 → A 继续。B 耗时 500ms，A 就等 500ms。

异步调用：A 把消息放进 MQ 就返回，B 从 MQ 取消息自己处理。A 响应时间只有"发消息"的耗时（< 1ms）。

```
同步（串行）：A ──[500ms]──→ B ──[500ms]──→ C   总耗时 1000ms
异步（MQ）：  A →MQ→ 立即返回（< 1ms）
                B 异步消费，C 异步消费，各自处理
```

**2. 解耦（Decoupling）**

直接调用时，A 需要知道 B 的地址/接口，B 挂了 A 报错，B 升级 A 要改代码。

通过 MQ：A 只需往 MQ 发消息，不关心谁消费、消费者在不在线。B 挂了消息积压在 MQ，B 恢复后继续消费。

```
紧耦合：A ──直接调用──→ B（B 挂了 A 报错）
         ↓
松耦合：A → MQ → B（B 挂了消息堆积，恢复后继续）
```

**3. 削峰（Peak Shaving）**

瞬时大流量（如秒杀）直接打 DB 会把 DB 打挂。通过 MQ 当缓冲，把流量"摊平"到时间轴上：

```
前端瞬时 10000 req/s → MQ → 后端按 DB 能处理的速率 1000 req/s 消费
```

### 什么时候不适合用 MQ

- 需要同步返回结果的场景（查询操作）
- 强一致性要求（ACID 事务场景，MQ 只保证最终一致）
- 消息量极小（引入 MQ 的运维成本大于收益）

---

## 二、RabbitMQ 核心模型（AMQP 协议）

### 核心组件关系

```
Producer（生产者）
    │
    ▼ publish(exchange, routingKey, message)
Exchange（交换机）── 按规则匹配 ──→ Queue（队列）──→ Consumer（消费者）
    │                               │
    └─ Binding（绑定）：             └─ ACK/NACK 确认机制
       exchange + routingKey → queue
```

### 各组件职责

| 组件 | 职责 |
|------|------|
| **Producer** | 发消息，指定 exchange 和 routingKey |
| **Exchange** | 接收消息，按路由规则分发到一个或多个 Queue |
| **Queue** | 存储消息，消费者从这里取 |
| **Binding** | Exchange 和 Queue 的绑定关系，附带一个 bindingKey |
| **RoutingKey** | 生产者发消息时指定，Exchange 用它和 bindingKey 匹配决定路由 |
| **Virtual Host** | 相当于命名空间，不同 vhost 里的 exchange/queue 完全隔离 |

### 关键概念：Exchange 不存储消息

Exchange 只做路由决策，不存消息。消息必须被路由到 Queue 才能保存。如果没有 Queue 与 Exchange 绑定，或路由不匹配，消息直接丢弃（可配置 alternate-exchange 兜底）。

---

## 三、Exchange 四种类型

### 1. Direct Exchange（直连）

**规则**：routingKey 必须与 bindingKey **完全相等**才路由。

```
Producer → exchange(direct) ──[bindingKey="order.create"]──→ Queue A
                              ──[bindingKey="order.cancel"]──→ Queue B

发 routingKey="order.create" → 只进 Queue A
发 routingKey="order.cancel" → 只进 Queue B
发 routingKey="order.update" → 无匹配，丢弃
```

**适用**：精确匹配，任务分发。

### 2. Topic Exchange（主题）

**规则**：routingKey 按 `.` 分词，bindingKey 支持通配符：
- `*` 匹配**一个**词
- `#` 匹配**零个或多个**词

```
Producer → exchange(topic)
  bindingKey="order.*"  → Queue A    // 匹配 "order.create"、"order.cancel"，不匹配 "order.pay.alipay"
  bindingKey="order.#"  → Queue B    // 匹配以 "order." 开头的一切
  bindingKey="notify.#" → Queue C    // 匹配以 "notify." 开头的一切
```

**项目用法**：`schedule.exchange` 是 Topic 类型，bindingKey `notify.#` 匹配所有 `notify.xxx` 路由键：
```
notify.booking_confirmed → notify.queue
notify.booking_cancelled → notify.queue
notify.rush_created      → notify.queue
（全部被 notify.# 捕获）
```

**适用**：按业务域路由，多种消息类型共享一个 exchange。

### 3. Fanout Exchange（广播）

**规则**：忽略 routingKey，把消息**广播**到所有绑定的 Queue。

```
Producer → exchange(fanout)
  → Queue A（所有绑定的 Queue 都收到）
  → Queue B
  → Queue C
```

**适用**：日志广播、通知所有实例刷新缓存。

### 4. Headers Exchange（头部）

**规则**：根据消息的 headers（键值对）路由，忽略 routingKey，bindingKey 改为 arguments 匹配规则。用得极少。

---

## 四、消息可靠性：三个环节的保证

消息从 Producer 到 Consumer 经过三个环节，每个环节都可能丢消息：

```
Producer ──①── Exchange ──②── Queue ──③── Consumer
  发送丢失        MQ宕机丢失         消费失败丢失
```

### 环节一：生产者 → MQ（Publisher Confirm 机制）

默认情况下 `rabbitTemplate.convertAndSend()` 是"发射后不管"的，MQ 收没收到不知道。

**Publisher Confirms**（发布确认）：MQ 收到并处理消息后，异步回调通知 Producer。

Spring AMQP 配置：
```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated  # 每条消息单独确认（对应项目 mhp-booking 的配置）
    publisher-returns: true             # 消息无法路由到 Queue 时回调 ReturnCallback
```

回调示例：
```java
rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
    if (!ack) {
        log.error("消息未被 MQ 接收，cause={}", cause);
        // 重发或报警
    }
});

rabbitTemplate.setReturnsCallback(returned -> {
    log.error("消息无法路由到 Queue，routingKey={}", returned.getRoutingKey());
    // 记录日志或重投
});
```

**项目中**：`mhp-booking` 的 `application.yaml` 配置了 `publisher-confirm-type: correlated`，这是生产端可靠性的基础配置。

### 环节二：MQ 本身（持久化）

MQ 收到消息后如果宕机，内存里的消息会丢失。必须持久化到磁盘。

三层持久化缺一不可：

```java
// 1. Exchange 持久化（durable=true）
ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();

// 2. Queue 持久化（durable=true）
QueueBuilder.durable(NOTIFY_QUEUE).build();

// 3. 消息本身持久化（deliveryMode=PERSISTENT）
// Spring AMQP 默认 MessageDeliveryMode.PERSISTENT，无需手动设置
```

**项目中**：`RabbitConfig.java` 里 exchange 和 queue 都设了 `durable(true)`，三层持久化均已配置。

### 环节三：消费者（手动 ACK）

消费者取到消息就开始处理，处理到一半崩溃了怎么办？

- **自动 ACK（默认）**：消息出队瞬间自动确认，Consumer 崩溃 → 消息永久丢失
- **手动 ACK**：处理完后主动调 `basicAck`，崩溃时消息重新入队等待重投

```java
// mhp-social application.yaml
spring.rabbitmq.listener.simple.acknowledge-mode: manual

// NotifyConsumer 中手动控制
channel.basicAck(tag, false);    // 处理成功，确认
channel.basicNack(tag, false, false);  // 处理失败，不重入队（进死信队列）
```

`basicNack` 的第三个参数 `requeue`：
- `true`：消息重新入队，会再次被消费（适合网络抖动等临时错误）
- `false`：消息不重入队，如果配了死信交换机则进死信队列（适合业务逻辑错误）

**项目选 requeue=false 的原因**：WebSocket 推送失败通常是 Consumer 端的持久性问题（用户根本不在线），无限重入队没意义，进死信队列留存供运维分析。

---

## 五、死信队列（Dead Letter Queue）

### 什么情况下消息变成死信

1. 消息被 Consumer 拒绝（`basicNack` / `basicReject` + `requeue=false`）
2. 消息在 Queue 里超过了 TTL（time-to-live）
3. Queue 达到最大长度，新消息被挤出

### 死信队列配置（项目实际代码）

```
正常流程：
schedule.exchange (Topic)
    "notify.#" → notify.queue (x-dead-letter-exchange=schedule.dead.exchange)

死信流程：
notify.queue 里的消息 basicNack(requeue=false)
    → 自动路由到 schedule.dead.exchange (Direct)
        "notify.dead.queue" → notify.dead.queue
```

```java
// 死信交换机：Direct 类型
DirectExchange scheduleDeadExchange = ExchangeBuilder.directExchange("schedule.dead.exchange").durable(true).build();

// 正常队列：声明死信交换机参数
Queue notifyQueue = QueueBuilder.durable("notify.queue")
    .withArgument("x-dead-letter-exchange", "schedule.dead.exchange")
    .withArgument("x-dead-letter-routing-key", "notify.dead.queue")
    .build();

// 死信队列：普通持久化队列
Queue notifyDeadQueue = QueueBuilder.durable("notify.dead.queue").build();
```

### 死信队列的价值

- **消息不丢失**：处理失败的消息不会消失，而是沉淀到死信队列
- **问题可追溯**：运维可以查看死信队列里的消息，分析失败原因
- **人工重投**：分析完问题后，可以把死信队列里的消息手动重新投递

---

## 六、延迟消息（TTL + DLX 模式）

RabbitMQ 原生不支持延迟队列，常用两种方案实现：

### 方案一：TTL + 死信队列（项目未用，但面试常问）

```
Producer → delay.queue (TTL=30min, DLX=process.exchange)
                    │
             消息超过 TTL，变成死信
                    │
process.exchange → process.queue → Consumer（30分钟后处理）
```

缺点：所有消息必须用同一个 TTL（Queue 级别设置），不能每条消息单独设不同延迟。
如果每条消息设不同 TTL（Message 级别），由于 RabbitMQ 按队列头部过期，中间有短TTL消息也无法提前过期。

### 方案二：rabbitmq-delayed-message-exchange 插件

安装插件后，Exchange 支持 `x-delayed-message` 类型，每条消息可设不同延迟时间：

```java
rabbitTemplate.convertAndSend(EXCHANGE, routingKey, msg, message -> {
    message.getMessageProperties().setDelay(1800000); // 30分钟，毫秒
    return message;
});
```

**项目中的定时任务选型**：本项目用的是 XXL-Job（预约超时取消、每日提醒），而非延迟消息。
XXL-Job 擅长周期性批量任务；延迟消息擅长"N 分钟后针对某条数据触发"的精细化延迟。

---

## 七、消费者幂等性

### 为什么会重复消费

RabbitMQ 保证 **At-Least-Once（至少一次）** 投递语义：
- Consumer 处理消息后 crash，来不及 ACK → MQ 重投
- 网络抖动，MQ 没收到 ACK → MQ 重投
- Consumer 手动 NACK + requeue=true → 重投

同一条消息可能被消费多次，Consumer 必须保证幂等。

### 幂等性设计方案

**方案一：Redis SET NX 去重（项目采用）**

```java
String idempotentKey = "msg:processed:" + msg.getMsgId();

// setIfAbsent = SET NX（Not eXists），原子操作
Boolean isNew = stringRedisTemplate.opsForValue()
    .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);

if (Boolean.FALSE.equals(isNew)) {
    // key 已存在 → 这条消息已处理过 → 直接 ACK 跳过
    channel.basicAck(tag, false);
    return;
}
// key 不存在 → 首次处理 → 继续业务逻辑
```

`msgId` 在 `MQSender` 里用 `IdUtil.fastSimpleUUID()` 生成，每次发消息时设置，MQ 重投时 msgId 不变。

**注意**：项目在处理失败时删除幂等 key（`stringRedisTemplate.delete(idempotentKey)`），允许下次重投时再处理，避免因为一次瞬时故障就永久跳过这条消息。

**方案二：数据库唯一索引**

消息体里携带业务唯一键，插入 DB 时依赖唯一索引报 DuplicateKeyException 来防重。

**方案三：状态机检查**

处理前查状态，只有特定状态才处理（适合有状态的业务流程）。

---

## 八、消息积压处理

### 什么情况下会积压

Consumer 消费速度 < Producer 发送速度，消息在 Queue 里堆积。常见原因：
- Consumer 挂了（积压最严重）
- 业务逻辑处理变慢（DB 慢查询、下游超时）
- 流量突增

### 处理方案

**紧急处理（救火）**：
1. 扩容 Consumer 实例数（最快）
2. 如果 Consumer 挂了，先修复重启
3. 消息已过期或业务允许丢弃：清空队列，接受损失

**预防积压（治本）**：
1. 设置 Queue 最大长度（`x-max-length`），防止无限积压
2. Consumer 限流：Spring AMQP 的 `prefetch-count`（每次只预取 N 条）

```yaml
spring.rabbitmq.listener.simple.prefetch: 1  # Consumer 每次只取 1 条，处理完再取下一条
```

---

## 九、消息序列化：Java 序列化 vs JSON

### 默认 Java 序列化的问题

Spring AMQP 默认使用 Java 原生序列化（`SimpleMessageConverter`），消息体是 Java 二进制格式：

```
问题一：可读性差  → RabbitMQ 管理后台看不懂消息内容，排查困难
问题二：强类型耦合 → 消费者必须有完全相同的类（包名、类名、serialVersionUID），
                     换语言或重构类名直接报错
问题三：安全风险  → Java 反序列化存在 RCE（远程代码执行）历史漏洞
```

### 换成 Jackson JSON（推荐）

```java
@Configuration
public class RabbitMQConfig {

    // 替换默认的 SimpleMessageConverter
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // RabbitTemplate 使用 JSON 序列化发消息
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    // Listener 容器工厂也使用 JSON 反序列化接收消息
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        return factory;
    }
}
```

配置后，MQ 里的消息变成标准 JSON，管理后台直接可读，且消费者不再依赖具体 Java 类的序列化版本。

### 消息 DTO 的设计原则

使用 JSON 序列化时，消息 DTO 无需实现 `Serializable`，但要注意：

```java
// 推荐写法：明确无参构造（Jackson 反序列化需要）
@Data
@Builder
@NoArgsConstructor   // ← 必须有，Jackson 反序列化用
@AllArgsConstructor
public class RenderMessage {
    private String taskId;
    private TaskType taskType;
    private Map<String, Object> payload;
}
```

---

## 十、Consumer 并发控制与资源隔离

### prefetch 和 concurrentConsumers 的区别

这两个参数经常混淆，但含义不同：

| 参数 | 含义 | 效果 |
|------|------|------|
| `prefetch=N` | 每个 Consumer 线程最多预取 N 条未 ACK 的消息 | 控制单个消费者的"手里拿着"几条消息 |
| `concurrentConsumers=N` | 启动 N 个 Consumer 线程并行消费 | 控制并发消费的线程数 |

```
prefetch=3, concurrentConsumers=2 的效果：

Consumer线程1 ──预取──→ [消息A, 消息B, 消息C]  并行处理
Consumer线程2 ──预取──→ [消息D, 消息E, 消息F]  并行处理

最多同时处理 6 条消息（2 × 3）
```

### 配置方式

**application.yml 方式**（简单场景）：
```yaml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 5           # 每个线程预取 5 条
        concurrency: 3        # 启动 3 个消费者线程
        max-concurrency: 10   # 流量高峰时最多扩到 10 个线程
```

**代码配置方式**（多队列、不同配置场景）：
```java
@Bean
public SimpleRabbitListenerContainerFactory renderContainerFactory(
        ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setConcurrentConsumers(1);
    factory.setMaxConcurrentConsumers(1);
    factory.setPrefetchCount(1);
    return factory;
}

// 使用时指定 containerFactory
@RabbitListener(queues = "render.queue", containerFactory = "renderContainerFactory")
public void consume(RenderMessage message) { ... }
```

### 资源约束场景：为什么要 prefetch=1 + concurrentConsumers=1

并不是所有场景都追求高并发。**当下游资源是独占瓶颈时，必须串行消费**：

```
场景：GPU 推理服务（TFAvatar 项目）
  GPU 显存有限，同时跑两个推理任务 → OOM 崩溃
  解决：prefetch=1 + concurrentConsumers=1，确保同一时刻只有一条消息在推理

场景：数据库连接池只有 5 个连接
  concurrentConsumers=5 是上限，超过会导致连接池耗尽

场景：第三方 API 有限流（100次/秒）
  需要通过并发数 + prefetch 联合控制调用速率
```

### Consumer 异常处理策略

Consumer 方法抛出异常时，Spring AMQP 的默认行为是：**无限重试**（消息重新入队，一直消费失败，一直重试，可能造成消息风暴）。

**推荐策略：捕获异常，不重新抛出，将失败状态持久化到 DB**：

```java
@RabbitListener(queues = "render.queue")
public void consume(RenderMessage message) {
    String taskId = message.getTaskId();
    taskService.markProcessing(taskId);

    try {
        doInference(message);  // 调用 GPU 推理
        taskService.markCompleted(taskId, resultPath);
    } catch (Exception e) {
        log.error("任务 {} 推理失败", taskId, e);
        taskService.markFailed(taskId, e.getMessage());
        // ⚠️ 不重新抛出异常：失败已记录到 DB，消息正常 ACK
        // 避免消息无限重入队造成消息风暴
    }
}
```

这个模式的核心思想：**MQ 负责传递消息，DB 负责记录状态**。业务失败不等于消息传递失败，两者不应混为一谈。

---

## 十一、Spring AMQP 编码实践

### 配置声明

```java
@Configuration
public class RabbitConfig {
    // 1. 声明 Exchange
    @Bean
    public TopicExchange myExchange() {
        return ExchangeBuilder.topicExchange("my.exchange").durable(true).build();
    }

    // 2. 声明 Queue（带死信配置）
    @Bean
    public Queue myQueue() {
        return QueueBuilder.durable("my.queue")
            .withArgument("x-dead-letter-exchange", "my.dead.exchange")
            .withArgument("x-dead-letter-routing-key", "my.dead.key")
            .build();
    }

    // 3. 绑定
    @Bean
    public Binding myBinding(Queue myQueue, TopicExchange myExchange) {
        return BindingBuilder.bind(myQueue).to(myExchange).with("my.#");
    }
}
```

### 生产者发消息

```java
@Component
@RequiredArgsConstructor
public class MyProducer {
    private final RabbitTemplate rabbitTemplate;

    public void send(String data) {
        MyMessage msg = new MyMessage();
        msg.setMsgId(UUID.randomUUID().toString()); // 幂等 ID
        msg.setData(data);
        rabbitTemplate.convertAndSend("my.exchange", "my.order.create", msg);
    }
}
```

### 消费者（手动 ACK）

```java
@Component
@RequiredArgsConstructor
public class MyConsumer {
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = "my.queue")
    public void onMessage(MyMessage msg, Message rawMsg, Channel channel) throws IOException {
        long tag = rawMsg.getMessageProperties().getDeliveryTag();
        String key = "msg:processed:" + msg.getMsgId();

        // 幂等检查
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            channel.basicAck(tag, false); // 重复，直接 ack 跳过
            return;
        }

        try {
            // 业务逻辑
            doProcess(msg);
            channel.basicAck(tag, false); // 成功，确认
        } catch (Exception e) {
            redisTemplate.delete(key); // 删除幂等 key，允许下次重试
            log.error("消息处理失败", e);
            channel.basicNack(tag, false, false); // 失败，进死信队列
        }
    }
}
```

### `basicAck` / `basicNack` 参数说明

```java
channel.basicAck(deliveryTag, multiple);
// deliveryTag：消息唯一序号（从 rawMsg 里取）
// multiple：true=确认所有 <= deliveryTag 的消息；false=只确认这一条

channel.basicNack(deliveryTag, multiple, requeue);
// requeue：true=重新入队；false=进死信队列（如果配了 DLX）

channel.basicReject(deliveryTag, requeue);
// 和 basicNack 类似，但只能拒绝单条，没有 multiple 参数
```

---

## 十二、项目实战：预约通知完整链路（mhp-booking）

### 完整流程图

```
mhp-booking：BookingServiceImpl.confirm()
    │
    ├─ 更新 booking.status = 2（已定档）
    └─ MQSender.sendBookingConfirmed(toUserId, bookingId)
            │
            ▼
    rabbitTemplate.convertAndSend(
        exchange="schedule.exchange",
        routingKey="notify.booking_confirmed",
        msg=NotifyMessage{msgId, type, toUserId, bookingId, content}
    )
            │
            ▼ Topic 匹配："notify.#" → notify.queue
            │
    ─── RabbitMQ 持久化消息 ───
            │
            ▼
    mhp-social：NotifyConsumer.onNotify()
            │
            ├─ 幂等检查：SET NX "msg:processed:{msgId}" 1 EX 86400
            │      已存在 → basicAck 直接跳过
            │
            ├─ 业务处理：messagingTemplate.convertAndSendToUser(
            │       userId, "/queue/notify", msg)
            │   → WebSocket STOMP 推给在线用户
            │
            ├─ 成功：channel.basicAck(tag, false)
            └─ 失败：channel.basicNack(tag, false, false)  → notify.dead.queue
```

### 六种消息类型

| 类型 | 触发方 | 目标 | 说明 |
|------|--------|------|------|
| `BOOKING_CONFIRMED` | 商家确认预约 | 客人 | "您的预约已被商家确认" |
| `BOOKING_COMPLETED` | 商家标记完成 | 客人 | "预约已完成，欢迎评价" |
| `BOOKING_CANCELLED` | 任意一方取消 | 另一方 | 商家取消通知客人，客人取消通知商家 |
| `SCHEDULE_REMINDER` | XXL-Job 每日 8:30 | 明日有档期的客人 | "明日档期提醒" |
| `RUSH_CREATED` | 商家创建抢档期 | 所有关注者（fan-out） | 消费端查 follow 表遍历推送 |
| `RUSH_REMINDER` | XXL-Job | 所有关注者（fan-out） | "5 分钟后抢档期开放" |

### fan-out 为什么在消费端做，不在生产端发多条

**生产端 fan-out**：MQSender 查出所有 follower，发 N 条消息（N = 粉丝数）。

**消费端 fan-out**（项目采用）：MQSender 发 1 条消息（只带 merchantId），NotifyConsumer 收到后查 follow 表，逐一推 WebSocket。

选消费端的原因：
1. **解耦**：booking 服务不需要查 follow 表（follow 数据在 social 服务），避免跨服务查询
2. **发送可靠**：只需确认 1 条消息被 MQ 接收，不是 N 条
3. **灵活**：消费端推送策略（在线/离线）只需改 social 服务，不影响 booking

---

## 十三、项目实战：TFAvatar GPU 渲染异步任务队列

### 问题背景

TFAvatar 是一个基于 3D Gaussian Splatting 的人体虚拟形象重建系统。GPU 推理单次耗时 **5~30 秒**，若直接在 HTTP 请求线程内同步等待推理结果：

```
客户端发请求 ──等待30秒──→ HTTP超时（Nginx默认60s）
同时多个用户请求 → 多个推理任务并发 → GPU显存溢出（OOM）
```

### 解决方案：RabbitMQ 异步 + MySQL 任务状态机

```
客户端
  POST /api/avatar/render
        │ 立即返回 taskId（< 5ms）
        ▼
  Spring Boot（Java）
        │ 写入 MySQL render_task（status=PENDING）
        │ 发布 RenderMessage 到 RabbitMQ
        ▼
  RabbitMQ（tfavatar.render.queue）
        │ prefetch=1，串行投递，保护 GPU
        ▼
  RenderConsumer（Java）
        │ 更新 status=PROCESSING
        │ 调用 Python 推理服务（HTTP RestClient）
        │ 接收 png_base64，解码保存到磁盘
        │ 更新 status=COMPLETED + resultImageUrl
        ▼
  客户端轮询 GET /api/task/{taskId}
        → status=COMPLETED 后访问 resultImageUrl 下载图片
```

### 关键设计决策

**① 任务状态机（4态）**

```
PENDING ──Consumer取到消息──→ PROCESSING ──推理成功──→ COMPLETED
                                           ──推理失败──→ FAILED
```

状态机存在 MySQL，即使 Java 服务重启，历史任务记录不丢失，前端可随时查询。

**② prefetch=1 + concurrentConsumers=1（GPU 串行保护）**

```java
factory.setConcurrentConsumers(1);   // 只有 1 个 Consumer 线程
factory.setMaxConcurrentConsumers(1);
factory.setPrefetchCount(1);          // 每次只取 1 条消息
```

GPU 是独占资源，两个推理任务并发必然导致显存溢出。`prefetch=1` 确保 Consumer 手里始终只有 1 条消息，前一条处理完才取下一条，实现天然的资源保护。

**③ 死信队列 + 消息 TTL（防消息积压）**

```java
Queue renderQueue = QueueBuilder.durable("tfavatar.render.queue")
    .withArgument("x-dead-letter-exchange", "tfavatar.dlx")
    .withArgument("x-dead-letter-routing-key", "dlq")
    .withArgument("x-message-ttl", 30 * 60 * 1000)  // 30分钟过期
    .build();
```

GPU 服务宕机时，消息不会无限积压：超过 30 分钟的消息自动进死信队列，运维可分析原因并决定是否重投。

**④ Consumer 不重抛异常（避免消息风暴）**

```java
@RabbitListener(queues = "tfavatar.render.queue")
public void consume(RenderMessage message) {
    try {
        doInference(message);
    } catch (Exception e) {
        taskService.markFailed(message.getTaskId(), e.getMessage());
        // 不 throw：失败状态已写入 DB，消息正常 ACK 消费完毕
        // 若 throw → 消息无限重投 → GPU 持续被打 → 雪崩
    }
}
```

### 完整 RabbitMQ 配置代码

```java
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE     = "tfavatar.direct";
    public static final String RENDER_QUEUE = "tfavatar.render.queue";
    public static final String RENDER_KEY   = "render";
    public static final String DLX_EXCHANGE = "tfavatar.dlx";
    public static final String DLX_QUEUE    = "tfavatar.render.dlq";

    @Bean public DirectExchange tfavatarExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    @Bean public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean public Queue renderQueue() {
        return QueueBuilder.durable(RENDER_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .withArgument("x-message-ttl", 30 * 60 * 1000)
                .build();
    }

    @Bean public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean public Binding renderBinding(Queue renderQueue, DirectExchange tfavatarExchange) {
        return BindingBuilder.bind(renderQueue).to(tfavatarExchange).with(RENDER_KEY);
    }

    @Bean public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();  // JSON 序列化
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setConcurrentConsumers(1);      // GPU 串行保护
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        return factory;
    }
}
```

---

## 十四、常见面试题

**Q：RabbitMQ 如何保证消息不丢失？**

三个环节三种保证：① 生产者 Publisher Confirm + Return Callback ② MQ 自身 exchange/queue/message 持久化 ③ 消费者手动 ACK（处理完再确认）。

**Q：如何保证消费者幂等？**

消息携带唯一 msgId，Consumer 处理前用 Redis `SET NX` 写入 `msg:processed:{msgId}`，返回 false 说明已处理，直接 ACK 跳过。

**Q：死信队列有什么用？**

消息在 `basicNack(requeue=false)` / TTL 过期 / 队列满时，自动路由到死信队列，消息不丢失。运维可以查死信队列分析失败原因，必要时手动重投。

**Q：Topic Exchange 和 Direct Exchange 有什么区别？**

Direct 精确匹配 routingKey；Topic 支持通配符（`*` 一个词，`#` 零或多个词），一个 Exchange 可路由多种消息到不同 Queue。

**Q：手动 ACK 和自动 ACK 的区别，什么时候用手动？**

自动 ACK：消息出队即确认，Consumer 挂了消息丢失。手动 ACK：处理完后才确认，失败可重投或进死信。只要业务不允许消息丢失就用手动 ACK。

**Q：什么是消息积压？如何处理？**

Consumer 消费速度跟不上 Producer 发送速度导致 Queue 堆积。紧急：扩容 Consumer 实例。预防：Consumer 加 `prefetch=1` 限流、设 Queue 最大长度。

**Q：`publisher-confirm-type: correlated` 和 `simple` 的区别？**

`simple`：所有消息共享一个确认回调，无法区分哪条消息失败。`correlated`：每条消息有独立的 `CorrelationData`，可以精确追踪哪条消息未确认。生产环境应用 `correlated`。

**Q：prefetch=1 和 concurrentConsumers=1 有什么区别，能否只设其中一个？**

含义不同：`prefetch=1` 控制单个线程最多预取几条消息；`concurrentConsumers=1` 控制线程数。若只设 `concurrentConsumers=1` 但 `prefetch=5`，这一个线程会同时持有 5 条消息并行处理，仍可能导致资源竞争。GPU 串行场景需两者同时设为 1。

**Q：为什么要用 JSON 序列化代替 Java 默认序列化？**

Java 序列化：消息不可读、强依赖类路径和 serialVersionUID、有反序列化安全漏洞。JSON 序列化：消息可读便于调试、跨语言（Python/Go 也能消费）、类重构不影响消息格式。Spring AMQP 中配置 `Jackson2JsonMessageConverter` 替换即可。

**Q：Consumer 抛出异常会怎样？应该如何处理？**

Spring AMQP 默认行为：异常 → 消息重新入队 → 无限重试 → 可能造成消息风暴。正确策略：在 Consumer 内部 try-catch，业务失败时将失败状态持久化到 DB（而非依赖 MQ 重投），消息正常 ACK。只有临时性错误（如网络抖动）才考虑 requeue=true 重投，并设置最大重试次数上限。


AMQP 模型（Advanced Message Queuing Protocol）

AMQP 是高级消息队列协议，RabbitMQ 就是基于 AMQP‑0‑9‑1 实现的。

核心思想：生产者不直接发消息给队列，发给交换机 Exchange；交换机按照路由规则，把消息投递到队列 Queue。

AMQP五大核心组件

1. Producer 生产者
发消息的程序，消息不会直接丢给队列，发送给 Exchange（交换机）。
2. Exchange 交换机
接收生产者消息，决定消息该往哪个队列走。
本身不存消息，只做转发。
3. Binding 绑定
 交换机 ↔ 队列  之间的绑定关系。
绑定的时候带上 routing‑key（路由键）。
4. Queue 队列
真正存储消息的地方。消费者从队列拿消息。
可以多个消费者监听同一个队列。
5. Consumer 消费者
从 Queue 获取消息，执行业务。

 

完整流程（AMQP标准流程）

1. 生产者发送消息 → Exchange交换机，消息带上  routing‑key 
2. Exchange 根据自己的类型 + 消息的  routing‑key ，查找绑定关系Binding
3. 把消息路由到匹配的 Queue（一个或多个）
4. 消息保存在队列 Queue
5. Consumer 从 Queue 消费消息

❗重点区别：Kafka没有交换机概念，生产者直接发给topic。RabbitMQ(AMQP)必须经过交换机。

Exchange 四种常用类型（面试高频）

1. Direct 直连交换机

- 规则：routing‑key 必须完全相等才匹配
- 使用场景：点对点，定向发给指定队列。
- 例子：绑定routing‑key= error ；只有消息routing‑key= error 才进这个队列。

2. Fanout 广播交换机

- 忽略 routing‑key
- 收到消息，把消息复制给所有绑定到此交换机的队列
- 场景：广播，所有订阅者全部收到。

3. Topic 主题交换机

- routing‑key支持通配符模糊匹配
-  *  匹配一个单词
-  #  匹配0个或者多个单词
- 示例： order.* 、 log.# 
- 场景：日志收集，多条件订阅。

4. Headers 头交换机

不靠routing‑key，靠消息 headers 属性匹配，很少用。

几个名词分清

- Routing‑key：生产者发消息时贴在消息上的标签
- Binding‑key：交换机绑定队列的时候写的匹配规则

Direct模式下两者完全一样；Topic模式下binding‑key可以带通配符。

AMQP协议还规定了什么？

不仅仅是模型，还规定整套：

1. 消息格式（header、payload）
2. 可靠性机制：confirm确认、ack/nack拒绝消息、持久化
3. 事务、死信队列、TTL
4. 网络二进制帧格式

RabbitMQ严格遵守这套AMQP模型。

面试口述简短版

AMQP是RabbitMQ使用的消息协议，模型分为生产者、交换机、绑定、队列、消费者。生产者消息发给交换机，交换机根据类型和路由键，通过绑定关系路由到队列，消费者从队列消费。交换机分direct、fanout、topic、headers四种。

容易踩坑

1. 生产者不能直接发送消息到队列，AMQP规范必须走交换机；只是RabbitMQ有默认交换机可以简写，底层依然走交换机。
2. 队列才存消息，交换机不存消息。

如果你需要，我可以对比：AMQP vs Kafka协议的区别。


RabbitMQ ack / nack，没有死信队列会发生什么

核心结论：
当你调用  nack  /  reject ，如果不配置死信队列(DLX)，消息不会直接丢弃，默认会重新回到原队列头部，重新被消费，不是直接扔了！

❗很多人记错：nack不等于丢弃。丢弃是额外参数控制的。

两个API

1.  basicNack(deliveryTag, multiple, requeue) 
2.  basicReject(deliveryTag, requeue) 
 
-  requeue = true ：消息重新放回原队列（默认经常是true）
                                                                                消息回到队列，会再次投递给消费者，很容易造成无限循环重复消费。
-  requeue = false ：丢弃这条消息；如果此时配置了死信队列，消息就转发进死信；没有死信队列，直接彻底删掉消息。

场景1：nack，requeue=true，无死信队列

java

channel.basicNack(tag, false, true);
 
 
消息 → 退回原队列头部 → 马上又推给消费者 → 死循环，不断重复消费同一条坏消息

这是线上非常经典bug：消息一直消费失败，疯狂重试，CPU打满。

场景2：nack，requeue=false，无死信队列

java

channel.basicNack(tag, false, false);
 
 
没有死信队列 → 直接永久删除消息，彻底丢失。

场景3：nack，requeue=false，配置了死信队列DLX

消息不会丢，转发到死信队列，等待人工排查。

什么时候消息会进死信队列？4种条件

1. nack / reject，并且  requeue=false 
2. 消息TTL过期
3. 队列达到最大长度，消息被溢出
4. 消费者断开，消息没有ack，也不会进死信；只有上面三种才会触发DLX路由

⚠️注意： requeue=true  的消息，永远不会进入死信队列，只是重新放回原队列。

面试一句话背诵

nack的行为由requeue参数决定：requeue=true消息重回原队列，会重复消费，不会进死信；requeue=false消息才会判断是否有死信队列，有就转发DLX，没有就直接丢弃消息。

开发最佳实践

1. 业务处理异常：
- 可以短暂重试几次；重试失败后，使用  nack(requeue=false) ，配合死信队列保存坏消息，方便排查。
  2. 千万不要一直  requeue=true ，会出现死循环。

补充区分 ack

basicAck() ：正常确认，消息直接删除，和死信队列无关。

 

举个通俗比喻：

- requeue=true：快递坏了，退回快递站，再派送给你，一遍一遍送
- requeue=false：拒收快递；有垃圾收纳盒（死信）就丢盒子，没有直接扔垃圾桶。

要不要我简单讲下死信队列完整配置逻辑？

basicAck(deliveryTag, multiple)

java

channel.basicAck(deliveryTag, multiple);
 
 
第二个参数 multiple：布尔值 true / false，批量确认

deliveryTag：每条消息的唯一编号，RabbitMQ给的，每一条消息都有。

multiple = false（默认常用）

只确认当前这一条消息。
只把传入的这个  deliveryTag  对应的消息标记为消费成功，删掉。

java

channel.basicAck(tag, false);
 
 
我确认这条，别的消息我不管。

multiple = true 批量确认

确认deliveryTag以及所有比它小的deliveryTag全部确认成功。

举例：
现在你收到消息tag：5、6、7、8
你调用： basicAck(8, true) 
含义：tag=1~8 全部确认消费完成，全部删掉。

 

它解决什么场景？

RabbitMQ可以开启 预取（prefetch），一次性推多条消息给消费者，消费者缓存一堆消息在本地，不是处理一条ack一条。

比如 prefetch=10，MQ一次性发给你10条消息。
你顺序处理完第10条，直接  ack(10,true) ，一次性确认全部10条，不用循环调用10次ack，减少网络IO。

⚠️巨大坑（面试高频）

1. 消息是按顺序投递才适合用 multiple=true；如果消息处理是多线程乱序处理，千万不能开multiple=true！

举个坑例子：
你收到 tag=5、tag=6。

- 5处理慢，6先处理完。
- 你执行  basicAck(6, true) 
👉 multiple=true：tag≤6 的全部确认，连还没处理完的tag=5也被确认删掉，直接消息丢失！

多线程异步消费，一定写 multiple=false，一条一条ack，不要批量。

和 nack 的 multiple 做对比

java

// nack第一个multiple参数
basicNack(deliveryTag, multiple, requeue)
 
 
nack的 multiple=true ：把 ≤ 当前tag所有未ack消息一起nack拒绝。

面试口述版

basicAck第二个参数multiple用来批量确认。false只确认单条；true代表确认deliveryTag以及之前所有收到的消息。多线程乱序消费必须用false，否则会丢消息；顺序消费可以用true减少网络往返。

小结记忆

- multiple=false：只干这1条
- multiple=true：这条 + 前面所有，一锅端

补充

basicReject 没有multiple参数，只能拒绝单条消息，不能批量拒绝，这就是为什么有basicNack。

要不要我顺带梳理一遍：basicAck / basicNack / basicReject三者完整对比表格？

RabbitMQ 生产者 ConfirmCallback 回调（发布确认）

概念：生产者发送消息出去之后，不知道 MQ 到底有没有收到这条消息。
 ConfirmCallback  就是一个回调函数：MQ收到消息之后，会给生产者回一个应答，执行这个回调。

属于 生产者可靠性，防止消息在网络途中丢了。

两个回调

1. ConfirmCallback：确认 MQ broker 是否收到这条消息
- ack=true：MQ 已经成功收下这条消息
- ack=false：MQ 拒绝接收这条消息（比如exchange不存在、内部异常）
  2. ReturnCallback：消息到达MQ，但是找不到队列，路由失败才触发（消息被退回生产者）

区分记忆

- Confirm：消息有没有到达Broker服务器
- Return：到达Broker，但是路由不到任何队列

底层AMQP原理

开启发布确认模式： publisher‑confirm‑type 
生产者每发一条消息，MQ会返回一个confirm。

- 同步：发完消息阻塞等待confirm返回（性能差）
- 异步：设置ConfirmCallback回调（我们业务常用），MQ回应答的时候自动执行回调方法，不阻塞业务线程。

SpringBoot 伪代码

java

// 设置确认回调
rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
if (ack) {
// MQ成功收到消息
System.out.println("消息成功投递到MQ");
} else {
// MQ拒收，消息没到broker
System.out.println("投递失败，原因："+cause);
// 这里要做补偿：重发、记录数据库，后续重试
}
});
 
 
-  correlationData ：消息唯一ID，用来标记是哪一条消息的回执。发消息的时候带上这个id，回调回来可以拿到。
-  ack ：boolean，true MQ收到；false MQ没有收下。
-  cause ：失败原因字符串。

重点坑（面试高频）

1. ✅ ack=true  只代表消息成功到达Broker（交换机）！
不代表消息已经成功投递到队列，更不代表消费者消费成功！

如果交换机收到消息，但是路由不到队列。此时 ConfirmCallback 的 ack依旧是 true！
因为消息已经到达broker，只是路由失败。这种情况触发 ReturnCallback。

举例子：
生产者发消息给exchangeA，但是没有任何队列绑定。

- ConfirmCallback → ack=true（已经送到交换机）
- ReturnCallback 被触发，告诉生产者：消息路由失败。

2. Confirm回调不能保证消息不丢队列这一步，需要同时开启 ReturnCallback。
3. 必须在配置文件开启发布确认，否则回调不会执行

yaml

spring:
rabbitmq:
publisher-confirm-type: correlated   # 开启confirm回调
publisher-returns: true              # 开启return回调
 
 
整个消息链路可靠性完整回顾

1. 生产者 → MQ交换机：靠 ConfirmCallback + ReturnCallback 保证
2. 交换机 → 队列：队列持久化、消息持久化
3. 队列 → 消费者：靠消费者端 basicAck

面试背诵简短版

ConfirmCallback是生产者发布确认回调。当消息到达Broker后回调，ack=true代表MQ收到消息，但不等于消息进队列；ack=false代表Broker拒收消息。如果消息到达交换机但是路由不到队列，confirm依旧true，此时触发ReturnCallback。一般结合消息id做消息补偿重试。

容易混淆对比

回调 什么时候触发 含义
ConfirmCallback 消息抵达Broker交换机 确认服务器是否收到消息
ReturnCallback 已到Broker，路由不到队列 消息被退回生产者
消费者Ack 消费者处理业务 确认消费者处理完毕

你这句话说到本质了，MQ本身根本不知道有这条消息存在，这个点面试特别爱考。

场景：网络丢包，生产者发出消息，半路网络丢了，没到达Broker

1. 数据包在网络上消失，RabbitMQ服务器完全没收到字节。
2. Broker根本不知道有这条消息，自然也不会给生产者返回任何 confirm 回执。
3. 此时  ConfirmCallback  不会被执行，既不会ack=true，也不会ack=false，回调一动不动，卡住。

⚠️关键点：
 ack=false  不是“网络丢包”触发；
 ack=false  是 MQ收到你的请求，但是明确拒绝了你（比如交换机不存在、权限错误、内部报错），才会返回false回执。

纯粹网络丢包：MQ连请求都看不到，就没有任何回执。ConfirmCallback不会跑。

 

那这种“消息发出去石沉大海”怎么处理？

Confirm回调解决不了网络丢包，因为对方根本没应答。
解决方案思路：生产者本地维护记录 + 超时判断

1. 发送消息前，在数据库存一条消息记录： msgId、内容、状态：待确认 
2. 发送消息，带上  CorrelationData(msgId) 
3. 如果收到  ConfirmCallback ：
- ack=true → 更新数据库状态为【投递成功】
- ack=false → 更新为【投递失败】，可以重试
  4. 超时扫描任务：定时轮数据库，找出超过一定时间还处于「待确认」状态的消息。

这些就是没有收到任何confirm回执，大概率网络丢包，重新发送。

简单总结三种生产者情况

1. 消息成功抵达Broker → ConfirmCallback ack=true
2. 消息到达Broker，但是MQ拒绝处理（exchange不存在）→ ConfirmCallback ack=false
3. 网络丢包，消息没到Broker → MQ一无所知，ConfirmCallback完全不触发，无任何回调

 

容易踩坑的认知误区

❌错误想法：只要消息没到MQ，confirm就返回false。
✅真实：false是MQ主动拒绝应答；网络丢包是没有应答。

所以只靠Confirm回调是不够保证生产者可靠性，必须配合本地数据库记录+超时补偿。

顺带区分 ReturnCallback

ReturnCallback的前提也是消息已经到达Broker；
如果网络丢包，ReturnCallback同样也不会执行。

 

面试口述版

如果消息网络丢包没有到达MQ，Broker完全感知不到这条消息，不会产生任何confirm回执，ConfirmCallback不会执行。ack=false是Broker收到请求后主动拒绝才返回，不是网络丢包场景。针对这种石沉大海的情况，需要生产者本地数据库记录消息，定时扫描超时未得到回执的消息做重试补偿。

ConfirmCallback vs ReturnCallback 核心区别

先记住大前提：两个回调全部触发的必要条件：消息数据包已经到达 RabbitMQ Broker。

如果网络丢包，数据包根本没到MQ：两个回调全都不执行。

ConfirmCallback（发布确认回调）

作用：确认 Broker（交换机）有没有收到这条消息
触发两种情况：

1.  ack = true ：Broker成功接收这条消息。

⚠️仅仅代表消息交到交换机手上！不代表消息进队列，不代表路由成功。
哪怕交换机收到，但是没有队列绑定，ack依旧是true！

2.  ack = false ：Broker收到你的请求，但是明确拒绝接收消息。
例子：交换机不存在、权限错误、内部异常，MQ收到包但是不收这条消息。

参数： CorrelationData(消息ID), ack布尔, cause失败原因 
 
ReturnCallback（消息退回回调）

作用：消息已经到达Broker交换机，但是路由失败，找不到任何匹配队列，消息被打回生产者。

触发条件：

- 交换机收到消息（Confirm一定是ack=true）
- 根据routing‑key找不到任何队列
- 必须开启配置  publisher‑returns: true 
 
此时 ConfirmCallback 执行(ack=true)，紧接着执行 ReturnCallback。

Return拿到：消息本体、exchange、routingKey、错误码。

 

4种场景完整走一遍，彻底分清

场景1：正常，消息成功路由到队列

1. 消息抵达Broker交换机
2. ConfirmCallback →  ack=true  ✔
3. ReturnCallback：不触发

场景2：消息到交换机，但是路由不到队列（无绑定队列）

1. 消息抵达Broker交换机
2. ConfirmCallback →  ack=true  ✔（交换机收到了！）
3. ReturnCallback 触发，告诉你路由失败，消息丢在MQ这边。

这是面试最高频坑：很多人以为路由失败confirm会false，实际是true！

场景3：交换机不存在，Broker拒收消息

1. 数据包到达Broker
2. ConfirmCallback →  ack=false ，给出拒绝原因
3. ReturnCallback：不会触发（MQ直接拒绝，消息根本没交给交换机处理）

场景4：网络丢包，数据包半路消失，没到MQ

1. MQ完全不知道这条消息
2. ConfirmCallback：不执行
3. ReturnCallback：不执行

需要生产者本地数据库+定时超时任务兜底。

 

表格汇总

项目 ConfirmCallback ReturnCallback
什么时候触发 Broker收到消息，给生产者应答 消息到交换机，路由失败找不到队列
ack=true含义 Broker成功收下消息（到交换机） 不会有ack，直接拿到被退回的完整消息
路由失败无队列 ack依旧true ✅触发
交换机不存在被拒绝 ack=false ❌不触发
网络丢包没到MQ 不回调 不回调
配置开关 publisher‑confirm‑type publisher‑returns: true
拿到的数据 消息ID、ack布尔、失败原因 完整消息内容、exchange、routingKey

面试背诵短句

Confirm确认消息有没有送到Broker交换机；Return代表已经送到交换机，但是路由不到队列。
路由失败的时候Confirm是ack=true，同时触发Return；交换机不存在拒绝消息，Confirm为false，Return不触发；网络丢包两个回调全都不执行，需要业务层数据库做超时补偿。

业务开发怎么搭配用

1. Confirm ack=false：立刻重试/记录失败
2. Confirm ack=true，但是进入ReturnCallback：说明消息到MQ但是发不进队列，记录日志告警，人工检查绑定关系
3. 两个回调都迟迟不来：定时任务扫描数据库超时消息，重试。

注意：Return回调拿到消息，代表这条消息已经在MQ侧被丢弃了，不会存入任何队列。

需要我顺带梳理：生产者消息丢失全部场景清单吗？
Broker 和 Exchange（交换机）不是同一个东西

Broker：指整个 RabbitMQ 服务实例（一整个MQ服务器进程）
Exchange（交换机）：只是 Broker 内部的一个组件对象。

打个房子的比方：

- Broker = 整栋大楼（RabbitMQ服务）
  大楼里面包含：交换机Exchange、队列Queue、绑定Binding、虚拟主机vhost、连接、用户权限，全部都在Broker里面。
- Exchange交换机 = 大楼里面的「分拣前台」
  生产者消息送到大楼（Broker），交给前台交换机，交换机负责分拣转发。
- Queue队列 = 大楼里面的储物房间，存放消息

 

对应之前Confirm回调逻辑，重新翻译一遍

ConfirmCallback ack=true ：消息已经送到Broker大楼内部，并且交给交换机这个前台了。
仅仅代表消息进了大楼，不代表消息成功送进储物房间（队列）。

送到大楼交给前台（ack=true），但是前台发现没有对应的房间可以送，就触发  ReturnCallback ，消息在Broker内部直接丢弃。

场景：交换机不存在

生产者发送消息，指定一个不存在的exchange。
消息数据包到达Broker（大楼收到你的网络请求），但是大楼里没有这个分拣前台。
于是Broker直接拒绝这条消息 →  Confirm ack=false ，根本不会走到路由逻辑，Return不会触发。

关键点区分：

1. 消息抵达Broker：TCP数据包成功到达MQ服务进程。
2. 交给Exchange交换机：Broker内部去找你指定的交换机组件。

- 第一步没成功（网络丢包）：连Broker都没到，无任何回调。
- 第一步成功，第二步失败（交换机不存在）：ack=false。
- 第一步、第二步都成功，但是路由不到队列：ack=true，触发Return。

 

Broker内部有很多组件，全部在一个服务进程里

Broker（RabbitMQ服务进程）包含：

1. vhost 虚拟主机（隔离命名空间）
2. Exchange 交换机（分拣，不存消息）
3. Queue 队列（真正存储消息）
4. Binding 绑定关系（交换机→队列的规则）
5. 连接、信道channel、权限管理、持久化数据

一台机器可以启动多个Broker（多个RabbitMQ实例，端口不一样），组成集群。每个Broker内部各自有一套交换机、队列。

面试容易混淆名词小结

1. Broker：整个RabbitMQ服务实例。
2. Exchange交换机：Broker内部，做消息路由转发，不存消息。
3. Queue队列：Broker内部，存储消息。
4. Channel信道：一条TCP连接里面分出多个轻量逻辑通道，我们API操作都是在channel上。

一句话背诵

Broker是完整的MQ服务；交换机只是Broker内部负责路由转发的组件。消息ack=true代表消息到达Broker并且交付给交换机，不等于消息进入队列。

✅完全正确，就是这个逻辑。

流程拆解：

1. 网络数据包成功到达 Broker（RabbitMQ服务进程），TCP通信没问题。
2. Broker解析你的AMQP报文：你要往名字叫  xxx_exchange  的交换机发消息。
3. Broker在自己内部去找这个交换机对象：找不到。
4. Broker直接拒绝这条消息，不会走到路由、不会走到队列，ReturnCallback完全不会触发。
5. 给生产者回复 confirm 回执： ack = false ，同时带上失败原因（unknown exchange）。

重点：

- 已经到达Broker，只是Broker内部找不到你指定的交换机 →  confirm ack=false 
- ReturnCallback 是交换机已经存在，消息成功给到交换机之后，路由找不到队列才会触发。

对比两条失败链路

1. ❌交换机不存在
网络 → Broker收到包 → 无此交换机 → confirm(ack=false)，Return不执行
2. ✅交换机存在，但是路由不到队列
网络 → Broker收到包 → 找到交换机，交给交换机处理 → 没有匹配队列 → confirm(ack=true) + 触发ReturnCallback

再对比网络丢包

数据包半路丢了，根本没抵达Broker
→ Broker什么都看不到，confirm回调完全不跑，既没有true，也没有false。

面试一句话

ack=false 的前提一定是消息已经到达Broker，只是Broker处理的时候发现异常（交换机不存在、权限不足等），主动拒绝消息；
没到Broker的网络丢包，是没有任何confirm回执。

这个坑面试特别喜欢问：

“confirm的ack=false是不是网络丢包？”
❌不是，ack=false代表已经到Broker，Broker明确拒绝。网络丢包是没有回调。
RabbitMQ（AMQP）消息队列完整面试笔记

一、基础概念

1. Broker：指一整个RabbitMQ服务实例进程。一个Broker内部包含vhost、交换机、队列、绑定、信道、用户权限。一台机器可以启动多个Broker构成集群。
2. vhost虚拟主机：Broker内的命名隔离空间，类似数据库，不同vhost的交换机、队列互相隔离。
3. Producer生产者：发送消息；Consumer消费者：消费消息。
4. Channel信道：一条TCP连接内部划分多条逻辑信道。

TCP连接很重，不要每条消息新建TCP；复用TCP，多个Channel，每个Channel独立做AMQP操作。

二、AMQP五大模型组件

AMQP‑0‑9‑1是RabbitMQ遵循的协议；生产者不直接发给队列，发给Exchange交换机

1. Exchange交换机：只负责路由转发，不存储消息。接收生产者消息，根据类型 + routing‑key + binding关系投递消息到队列。
2. Binding绑定：交换机 ↔ 队列之间的绑定关系，绑定的时候指定binding‑key。
3. Queue队列：真正存储消息，消费者从队列拿消息。
4. Routing‑key：生产者发消息，贴在消息上的标签。
5. Binding‑key：交换机绑定队列写的匹配规则。

Direct模式：routing‑key与binding‑key完全相等；Topic模式binding‑key可以带通配符。

Exchange四种类型

1. Direct直连：routing‑key完全相等才匹配。点对点定向投递。
2. Fanout广播：忽略routing‑key，消息复制发给所有绑定该交换机的队列。做广播通知。
3. Topic主题：通配符匹配。 * 匹配一个单词； # 匹配0或多个单词；常用于日志。
4. Headers头交换机：不靠routing‑key，靠消息headers匹配，几乎不用。

完整消息流转

生产者 →(routing‑key)→ Exchange交换机 → 根据binding规则 → Queue队列 → 消费者消费

注意：RabbitMQ有默认交换机，可以直接发消息到队列，底层依然走direct默认交换机。

三、生产者可靠性（防止生产者丢消息）

1. ConfirmCallback 发布确认回调

配置开启： publisher‑confirm‑type: correlated 
 
含义：消息数据包到达Broker（RabbitMQ服务）之后，Broker给生产者回执回调。

-  ack=true ：消息已经成功到达Broker，并且交付给交换机。

⚠️不等于消息进入队列！只是交到交换机手上！
交换机收到消息，但是路由不到队列，confirm依旧ack=true。

-  ack=false ：数据包到达Broker，但是Broker明确拒绝这条消息。
  场景：指定的交换机不存在、权限错误、内部异常。

❗网络丢包：数据包根本没抵达Broker。ConfirmCallback完全不执行，没有true也没有false，MQ完全不知道这条消息存在。

2. ReturnCallback 退回回调

配置开启： publisher‑returns: true 
触发条件：消息到达Broker、交给交换机，但是路由失败，找不到匹配队列。

- Confirm回调ack=true，紧接着触发ReturnCallback。
- Return可以拿到完整消息体、exchange、routingKey。
- 触发Return代表这条消息在MQ侧直接丢弃，不会存入队列。

对比记忆

1. 交换机不存在 → confirm ack=false，Return不触发
2. 交换机存在，路由不到队列 → confirm ack=true，触发Return
3. 网络丢包 → 两个回调全部不执行

3. 网络丢包如何兜底（回调解决不了）

Confirm/Return都迟迟不执行：生产者本地数据库记录消息，状态=待确认；定时任务扫描超时的待确认消息，进行重试。

- confirm ack=true：更新状态投递成功
- confirm ack=false：更新失败，可重试
- 超时未收到回执：判定大概率网络丢包，重试发送。

只靠Confirm、Return不能100%保证生产者不丢消息，必须业务层数据库兜底。

四、消费者可靠性（ACK机制，防止消费端丢消息）

MQ队列收到消息推送给消费者，消息不会直接删除；等待消费者回执ack。

basicAck(deliveryTag, multiple)

- deliveryTag：MQ分配给每条消息的唯一编号。
-  multiple=false ：只确认当前这一条消息。（多线程乱序消费必须用这个）
-  multiple=true ：确认deliveryTag以及所有比它小的tag全部确认成功，批量确认。

⚠️坑：多线程乱序消费严禁multiple=true，会把尚未处理完成的消息一并确认删除，消息丢失。适合顺序消费、prefetch预取多条消息场景，减少网络IO。

basicNack(deliveryTag, multiple, requeue)

可以批量拒绝消息

1.  requeue = true ：消息重新放回原队列头部。会造成无限循环重复消费（死循环bug），永远不会进入死信队列。
2.  requeue = false ：丢弃消息；如果配置死信队列DLX，消息转发死信；没有死信直接删除丢失。

basicReject(deliveryTag,requeue)

只能拒绝单条消息，没有multiple批量参数。

重点：requeue=true不会进死信队列！

五、死信队列 DLX（死信交换机）

消息变成死信的4种条件，才会转发死信交换机：

1. nack / reject，并且 requeue=false 
2. 消息TTL过期
3. 队列达到最大长度，消息溢出
4. 注意：消费者断开连接，消息没有ack，不会成为死信，消息重回队列。

requeue=true退回队列，不属于死信。

业务最佳实践：消费异常，重试几次失败，调用nack(requeue=false)，消息转入死信队列，人工排查。不要一直requeue=true，CPU打爆。

六、其他重要特性

1. 消息持久化

- 队列持久化：队列元数据存磁盘，Broker重启队列还在。
- 消息持久化：消息体写入磁盘，重启消息不丢；如果不持久化，内存消息重启全部丢失。

只有队列+消息同时持久化，消息才可以宕机不丢失。

2. prefetch预取
 channel.basicQos(prefetchCount) ，MQ一次性推多条消息给消费者本地缓存，不用消费一条发一条ack。提升吞吐量。

注意：预取多条，如果多线程消费，ack一定要multiple=false。

3. TTL消息过期
两种设置：消息级别TTL；队列级别TTL。过期消息成为死信。

七、消息丢失全链路梳理（面试高频）

1）生产者端丢失

1. 网络丢包，消息没到Broker → confirm无回调，本地DB+定时任务兜底
2. 到达Broker，交换机不存在 → confirm ack=false，业务重试
3. 到交换机，路由不到队列 → confirm ack=true，触发Return回调，告警

开启confirm+return，加上业务数据库记录+定时补偿。

2）MQ Broker端丢失

队列未持久化 / 消息未持久化；Broker宕机重启消息丢失。

解决方案：队列持久化、消息持久化；集群镜像队列。

3）消费者端丢失

还没有执行业务，代码异常，但是已经执行basicAck确认，消息被MQ删除。

解决方案：先执行业务，业务处理成功之后再手动ack；不要自动ack。
禁止自动Acknowledge自动确认模式。

八、消息重复消费问题

MQ不能保证消息只投递一次，只能保证至少投递一次（at‑least‑once）。
产生重复原因：网络抖动，消费者处理完业务，ack回执网络丢了。MQ没有收到ack，消息重新投递。

解决：业务做幂等性。

1. 数据库唯一消息id约束；
2. redis记录已经处理过的messageId；

MQ本身不能帮你消除重复，业务层实现幂等。

九、消息积压

现象：队列消息越来越多，消费跟不上生产。
原因：消费者处理慢；消费失败不断requeue重回队列；消费者下线。
排查：看队列消息数量。
处理：增加消费者；优化消费业务；临时导出消息。

十、SpringBoot配置关键参数

yaml

spring:
rabbitmq:
publisher‑confirm‑type: correlated   #开启confirm回调
publisher‑returns: true              #开启return回调
 
 
java

//生产者设置回调
rabbitTemplate.setConfirmCallback((correlationData,ack,cause)->{});
rabbitTemplate.setReturnsCallback(returnedMessage -> {});
 
 
CorrelationData携带消息唯一ID，confirm回调回来拿到，用来对应是哪一条消息回执。

十一、高频面试口述简答题

1. confirm和return区别？

confirm确认消息是否到达Broker交换机；return代表到达交换机但是路由不到队列。路由失败confirm依旧ack=true；交换机不存在confirm返回false，return不触发；网络丢包两个回调都不会执行。

2. nack requeue=true/false区别？

true消息放回原队列，不会进死信，容易死循环；false消息丢弃，有死信则转发死信。

3. multiple参数含义？

basicAck第二个参数，false单条确认；true批量确认tag以及之前所有消息。多线程消费禁止true。

4. 死信队列什么时候才会收到消息？requeue=true会进死信吗？

requeue=true不会进死信。只有requeue=false、TTL过期、队列满溢出才会。

5. AMQP模型，交换机、队列、绑定关系。

生产者发送给交换机，交换机根据binding和routing‑key路由消息到队列。交换机四种类型。

6. 如何保证消息不丢失？

生产者confirm+return +业务DB定时补偿；MQ队列消息持久化；消费者手动ack；集群高可用。

7. MQ消息会重复吗，怎么解决？

at‑least‑once至少一次投递，会重复，业务实现幂等。

如果你需要，我可以给你精简版（面试背诵版，压缩一半）。
