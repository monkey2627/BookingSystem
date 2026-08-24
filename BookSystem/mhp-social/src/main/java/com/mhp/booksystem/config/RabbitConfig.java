package com.mhp.booksystem.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（mhp-social 侧） — 与 mhp-booking 的 RabbitConfig 完全相同。
 *
 * 为什么两边都要声明？
 *   消费者服务（social）启动时，若 RabbitMQ 里还没有相应队列/交换机，
 *   @RabbitListener 会因绑定失败而抛出异常。
 *   两边都声明，无论哪个服务先启动都能正常运行，
 *   RabbitMQ 对相同参数的幂等声明不会报错。
 *
 * 拓扑见 mhp-booking 的 RabbitConfig 注释。
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE       = "schedule.exchange";
    public static final String DEAD_EXCHANGE  = "schedule.dead.exchange";
    public static final String NOTIFY_QUEUE   = "notify.queue";
    public static final String DEAD_QUEUE     = "notify.dead.queue";
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
