package com.mhp.booksystem.mq;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.mhp.booksystem.config.RabbitConfig.NOTIFY_QUEUE;

/**
 * 预约通知消费者 — 消费 mhp-booking 发来的通知，通过 WebSocket 推给目标用户。
 *
 * acknowledge-mode: manual（手动 ack）原因：
 *   自动 ack 会在消息出队瞬间就确认，若 WebSocket 推送失败，消息丢失。
 *   手动 ack 让我们在推送成功后才 basicAck，失败时 basicNack 让消息重回队列。
 *
 * 幂等性设计（消费者幂等）：
 *   RabbitMQ 在 basic.nack + requeue=false 后会把消息路由到死信队列。
 *   但网络抖动可能导致消息被投递两次（At-Least-Once 语义）。
 *   用 Redis SET NX "msg:processed:{msgId}" 记录已处理的消息 id，
 *   第二次消费时 setIfAbsent 返回 false，直接 ack 跳过，不重复推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyConsumer {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @RabbitListener(queues = NOTIFY_QUEUE)
    public void onNotify(NotifyMessage msg, Message rawMsg, Channel channel) throws IOException {
        long tag = rawMsg.getMessageProperties().getDeliveryTag();
        String idempotentKey = "msg:processed:" + msg.getMsgId();

        // 幂等检查：SET NX + 24h 过期，24h 内同一 msgId 只处理一次
        Boolean isNew = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("[MQ] 重复消息，跳过 msgId={}", msg.getMsgId());
            channel.basicAck(tag, false);
            return;
        }

        try {
            // convertAndSendToUser 内部会把 userId 和 /queue/notify 拼成
            // /user/{userId}/queue/notify，Spring 的 UserDestinationResolver
            // 确保只有该 userId 的 WebSocket 会话能收到
            messagingTemplate.convertAndSendToUser(
                    msg.getToUserId().toString(),
                    "/queue/notify",
                    msg
            );
            channel.basicAck(tag, false); // 推送成功，确认消息出队
            log.info("[MQ] 消息处理成功 msgId={} type={}", msg.getMsgId(), msg.getType());
        } catch (Exception e) {
            // 推送失败：删除幂等 key（允许下次重试），nack 不重入队（直接进死信队列）
            stringRedisTemplate.delete(idempotentKey);
            log.error("[MQ] 消息处理失败 msgId={} type={}", msg.getMsgId(), msg.getType(), e);
            channel.basicNack(tag, false, false); // requeue=false → 死信队列
        }
    }
}
