package com.mhp.booksystem.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.service.ReviewScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 预约通知消费者 — 消费 mhp-booking 发来的通知，通过 WebSocket 推给目标用户。
 *
 * RocketMQ 消费语义（对比 RabbitMQ 手动 ACK）：
 *   - onMessage 正常返回 → 消费成功（等价于 basicAck）
 *   - onMessage 抛出异常 → 消费失败，RocketMQ 按指数退避自动重试（默认 16 次）
 *   - 超过 maxReconsumeTimes 后，消息自动进死信 Topic：%DLQ%mhp-social-notify-consumer
 *   无需手动操作 Channel，框架自动管理重试和死信。
 *
 * 幂等性设计（保持不变）：
 *   RocketMQ 也是 At-Least-Once 语义，重试或故障恢复时同一消息可能再次投递。
 *   用 Redis SET NX "msg:processed:{msgId}" 记录已处理 id，
 *   第二次消费时 setIfAbsent 返回 false，直接 return（等价于 ack 跳过）。
 *
 * SCORE_UPDATE 消息：
 *   不推 WebSocket，调 ReviewScoreService 重新聚合商家评分并更新 DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "mhp-notify-topic",
        consumerGroup = "mhp-social-notify-consumer",
        selectorExpression = "*"   // 消费所有 tag，在逻辑内按 type 分支
)
public class NotifyConsumer implements RocketMQListener<NotifyMessage> {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final FollowMapper followMapper;
    private final ReviewScoreService reviewScoreService;

    @Override
    public void onMessage(NotifyMessage msg) {
        String idempotentKey = "msg:processed:" + msg.getMsgId();

        // 幂等检查：SET NX + 24h 过期，24h 内同一 msgId 只处理一次
        Boolean isNew = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("[MQ] 重复消息，跳过 msgId={}", msg.getMsgId());
            return;  // 正常返回 = 告知 RocketMQ 消费成功，不重试
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

        } catch (Exception e) {
            // 处理失败：删除幂等 key（允许 RocketMQ 重试时重新处理）
            stringRedisTemplate.delete(idempotentKey);
            log.error("[MQ] 消息处理失败 msgId={} type={}", msg.getMsgId(), msg.getType(), e);
            // 抛出异常通知 RocketMQ 本次消费失败，触发自动重试
            throw e;
        }
    }
}
