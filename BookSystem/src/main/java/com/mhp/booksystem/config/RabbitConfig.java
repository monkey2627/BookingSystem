package com.mhp.booksystem.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 是什么？
 *   RabbitMQ 是一个独立运行的"消息中间件"（消息队列服务器）。
 *   生产者把消息扔进队列，消费者从队列里取消息处理，两端完全解耦，互不等待。
 *
 * 为什么要用 RabbitMQ？
 *   场景：商家确认订单后，需要给客人发一条通知。
 *   如果在 confirm() 接口里直接发通知（调用短信/WebSocket），有两个问题：
 *     1. 通知渠道偶尔很慢甚至挂掉 → confirm 接口跟着超时，用户以为操作失败
 *     2. 通知是"不重要的副作用"，不应该拖慢核心业务的响应时间
 *   用 RabbitMQ 后，confirm() 只需把"发通知"这个任务扔进队列就立刻返回，
 *   消费者在后台异步处理，通知失败也有死信队列兜底，不影响主流程。
 *   一句话：用 MQ 把"立刻做"变成"放进队列，稍后做"，核心接口快，失败有重试。
 *
 * 本项目 MQ 拓扑（消息流向）：
 *   生产者 → schedule.exchange（Topic 交换机）
 *            ↓  路由键 notify.#
 *          notify.queue（主队列）
 *            ↓  消费者处理失败（basicNack）
 *          schedule.dead.exchange（死信交换机）
 *            ↓
 *          notify.dead.queue（死信队列，人工排查）
 *
 * Topic 交换机 vs Direct 交换机：
 *   Topic：路由键支持通配符（notify.# 匹配所有 notify. 开头的键），便于将来扩展通知类型
 *   Direct：精确匹配路由键，死信场景用它足够，简单可靠
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE       = "schedule.exchange";
    public static final String DEAD_EXCHANGE  = "schedule.dead.exchange";
    public static final String NOTIFY_QUEUE   = "notify.queue";
    public static final String DEAD_QUEUE     = "notify.dead.queue";
    public static final String NOTIFY_ROUTING = "notify.#";

    // 主交换机（Topic）：生产者把消息投递到这里，由它按路由键分发到队列
    @Bean
    public TopicExchange scheduleExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    // 死信交换机（Direct）：消费者处理失败且不重新入队时，消息从主队列转投到这里
    @Bean
    public DirectExchange scheduleDeadExchange() {
        return ExchangeBuilder.directExchange(DEAD_EXCHANGE).durable(true).build();
    }

    // 主队列：持久化（服务重启消息不丢），配置死信出口
    // x-dead-letter-exchange / routing-key：指定消息"死亡"后去哪里
    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(NOTIFY_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_QUEUE)
                .build();
    }

    // 死信队列：存放处理失败的消息，运维人员可在 RabbitMQ 管理台（localhost:15672）查看原因
    @Bean
    public Queue notifyDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    // 主队列 ← 主交换机，路由 notify.# 匹配所有通知类消息
    @Bean
    public Binding notifyBinding(Queue notifyQueue, TopicExchange scheduleExchange) {
        return BindingBuilder.bind(notifyQueue).to(scheduleExchange).with(NOTIFY_ROUTING);
    }

    // 死信队列 ← 死信交换机，精确路由键
    @Bean
    public Binding notifyDeadBinding(Queue notifyDeadQueue, DirectExchange scheduleDeadExchange) {
        return BindingBuilder.bind(notifyDeadQueue).to(scheduleDeadExchange).with(DEAD_QUEUE);
    }
}
