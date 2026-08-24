package com.mhp.booksystem.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明 — 定义交换机、队列和绑定关系。
 *
 * 同样的配置同时存在于 mhp-booking 和 mhp-social：
 *   两个服务都需要声明相同的拓扑，因为 RabbitMQ 是"幂等声明"，
 *   重复声明已存在且参数相同的资源不会报错，两个服务谁先启动谁创建。
 *
 * 死信队列（DLQ）设计：
 *   消息在 notify.queue 处理失败（basicNack + requeue=false）后，
 *   自动路由到 schedule.dead.exchange → notify.dead.queue。
 *   运维可以查看死信队列中的消息，分析失败原因，必要时手动重投。
 *
 * 拓扑图：
 *   schedule.exchange (Topic)
 *     → "notify.#" → notify.queue (DLX=schedule.dead.exchange)
 *                         │ nack
 *   schedule.dead.exchange (Direct)
 *     → "notify.dead.queue" → notify.dead.queue
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE       = "schedule.exchange";
    public static final String DEAD_EXCHANGE  = "schedule.dead.exchange";
    public static final String NOTIFY_QUEUE   = "notify.queue";
    public static final String DEAD_QUEUE     = "notify.dead.queue";
    /** Topic 通配符：notify.BOOKING_CONFIRMED / notify.SCHEDULE_REMINDER 等全部匹配 */
    public static final String NOTIFY_ROUTING = "notify.#";

    @Bean
    public TopicExchange scheduleExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange scheduleDeadExchange() {
        return ExchangeBuilder.directExchange(DEAD_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(NOTIFY_QUEUE)
                // 绑定死信交换机：消息 nack 后自动转发到死信队列
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_QUEUE)
                .build();
    }

    @Bean
    public Queue notifyDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding notifyBinding(Queue notifyQueue, TopicExchange scheduleExchange) {
        return BindingBuilder.bind(notifyQueue).to(scheduleExchange).with(NOTIFY_ROUTING);
    }

    @Bean
    public Binding notifyDeadBinding(Queue notifyDeadQueue, DirectExchange scheduleDeadExchange) {
        return BindingBuilder.bind(notifyDeadQueue).to(scheduleDeadExchange).with(DEAD_QUEUE);
    }
}
