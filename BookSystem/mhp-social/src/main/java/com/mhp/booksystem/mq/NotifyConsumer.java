package com.mhp.booksystem.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.service.ReviewScoreService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
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
 *
 * SCORE_UPDATE 消息处理：
 *   routing key "notify.score_update" 匹配 "notify.#"，进入本队列。
 *   不推 WebSocket，而是调 ReviewScoreService 重新聚合商家评分并更新 DB。
 *   这是评价提交后异步更新商家评分的消费端逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyConsumer {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final FollowMapper followMapper;
    private final ReviewScoreService reviewScoreService;

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
            if ("SCORE_UPDATE".equals(msg.getType())) {
                // 评价提交后异步更新商家评分，不推 WebSocket
                reviewScoreService.updateMerchantScore(msg.getMerchantId());
                log.info("[MQ] 评分更新完成 merchantId={}", msg.getMerchantId());

            } else if ("RUSH_CREATED".equals(msg.getType()) || "RUSH_REMINDER".equals(msg.getType())) {
                // fan-out：查所有关注该商家的用户，逐一推 WebSocket
                List<Follow> followers = followMapper.selectList(
                        new LambdaQueryWrapper<Follow>()
                                .eq(Follow::getMerchantId, msg.getMerchantId())
                );
                for (Follow f : followers) {
                    messagingTemplate.convertAndSendToUser(
                            f.getUserId().toString(),
                            "/queue/notify",
                            msg
                    );
                }
                log.info("[MQ] 抢档 fan-out type={} merchantId={} 推送 {} 位关注者",
                        msg.getType(), msg.getMerchantId(), followers.size());

            } else {
                // 普通单用户通知
                messagingTemplate.convertAndSendToUser(
                        msg.getToUserId().toString(),
                        "/queue/notify",
                        msg
                );
                log.info("[MQ] 消息处理成功 msgId={} type={}", msg.getMsgId(), msg.getType());
            }

            channel.basicAck(tag, false);

        } catch (Exception e) {
            // 处理失败：删除幂等 key（允许下次重试），nack 不重入队（进死信队列）
            stringRedisTemplate.delete(idempotentKey);
            log.error("[MQ] 消息处理失败 msgId={} type={}", msg.getMsgId(), msg.getType(), e);
            channel.basicNack(tag, false, false);
        }
    }
}
