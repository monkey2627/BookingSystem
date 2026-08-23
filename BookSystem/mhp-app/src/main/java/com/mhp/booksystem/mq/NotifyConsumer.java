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

        Boolean isNew = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("[MQ] 重复消息，跳过 msgId={}", msg.getMsgId());
            channel.basicAck(tag, false);
            return;
        }

        try {
            handleNotify(msg);
            channel.basicAck(tag, false);
            log.info("[MQ] 消息处理成功 msgId={} type={}", msg.getMsgId(), msg.getType());
        } catch (Exception e) {
            stringRedisTemplate.delete(idempotentKey);
            log.error("[MQ] 消息处理失败 msgId={} type={}", msg.getMsgId(), msg.getType(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private void handleNotify(NotifyMessage msg) {
        // 通过 STOMP 向目标用户的 /queue/notify 推送，仅该用户的 WebSocket 连接可收到
        messagingTemplate.convertAndSendToUser(
                msg.getToUserId().toString(),
                "/queue/notify",
                msg
        );
        log.debug("[WS] 推送通知 toUser={} type={}", msg.getToUserId(), msg.getType());
    }
}
