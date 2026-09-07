package com.mhp.booksystem.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "mhp-notify-topic",
        consumerGroup = "mhp-app-notify-consumer",
        selectorExpression = "*"
)
public class NotifyConsumer implements RocketMQListener<NotifyMessage> {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(NotifyMessage msg) {
        String idempotentKey = "msg:processed:" + msg.getMsgId();

        Boolean isNew = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("[MQ] 重复消息，跳过 msgId={}", msg.getMsgId());
            return;
        }

        try {
            handleNotify(msg);
            log.info("[MQ] 消息处理成功 msgId={} type={}", msg.getMsgId(), msg.getType());
        } catch (Exception e) {
            stringRedisTemplate.delete(idempotentKey);
            log.error("[MQ] 消息处理失败 msgId={} type={}", msg.getMsgId(), msg.getType(), e);
            throw e;
        }
    }

    private void handleNotify(NotifyMessage msg) {
        messagingTemplate.convertAndSendToUser(
                msg.getToUserId().toString(),
                "/queue/notify",
                msg
        );
        log.debug("[WS] 推送通知 toUser={} type={}", msg.getToUserId(), msg.getType());
    }
}
