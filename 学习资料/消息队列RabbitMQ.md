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
