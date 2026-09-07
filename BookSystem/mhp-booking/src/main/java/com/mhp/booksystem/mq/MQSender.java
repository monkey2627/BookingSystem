package com.mhp.booksystem.mq;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 预约事件消息发送器 — mhp-booking 生产消息，mhp-social 消费后推 WebSocket。
 *
 * 消息流向：
 *   BookingServiceImpl/ReminderJobHandler
 *     → MQSender.send()
 *       → RocketMQ Topic "mhp-notify-topic"，Tag = 消息类型（如 BOOKING_CONFIRMED）
 *         → mhp-social NotifyConsumer（consumerGroup=mhp-social-notify-consumer）
 *           → WebSocket /user/queue/notify → 浏览器
 *
 * 为什么用 MQ 而不是直接 Feign 调 social 推 WebSocket？
 *   1. 解耦：booking 不依赖 social 服务是否在线，消息可以积压等 social 恢复。
 *   2. 可靠性：syncSend 同步确认，发送失败立即抛异常，上层可感知并处理。
 *   3. 异步：booking 不需要等通知发送完成才返回响应给客人。
 *
 * 为什么换 RocketMQ（面试答：对比 RabbitMQ）：
 *   1. 国内主流，阿里系生态，面试高频且与 Spring Cloud Alibaba 天然集成。
 *   2. 内置延迟消息（18 个延迟级别），无需 TTL+死信的迂回实现。
 *   3. 消费失败自动重试（默认 16 次指数退避），超次后自动进 %DLQ% 死信 Topic，比 RabbitMQ 手动配置简洁。
 *   4. 消息轨迹（Message Trace）可通过 Dashboard 追踪每条消息的生产/消费全链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MQSender {

    private static final String TOPIC = "mhp-notify-topic";

    private final RocketMQTemplate rocketMQTemplate;

    /** 商家确认预约后，通知客人 */
    public void sendBookingConfirmed(Long toUserId, Long bookingId) {
        send(toUserId, bookingId, "BOOKING_CONFIRMED", "您的预约已被商家确认，请按时赴约！");
    }

    /** 商家标记完成后，通知客人可以评价 */
    public void sendBookingCompleted(Long toUserId, Long bookingId) {
        send(toUserId, bookingId, "BOOKING_COMPLETED", "预约已完成，欢迎留下评价~");
    }

    /**
     * 取消通知：
     *   isMerchantCancel=true  → 通知客人（toUserId=客人 userId）
     *   isMerchantCancel=false → 通知商家（toUserId=商家 userId）
     */
    public void sendBookingCancelled(Long toUserId, Long bookingId, boolean isMerchantCancel) {
        String content = isMerchantCancel ? "商家已取消本次预约，如有疑问请联系商家。" : "您的预约已取消。";
        send(toUserId, bookingId, "BOOKING_CANCELLED", content);
    }

    /** ReminderJobHandler 每日提醒：明日有档期的客人 */
    public void sendScheduleReminder(Long toUserId, Long bookingId, String dateStr) {
        send(toUserId, bookingId, "SCHEDULE_REMINDER", "温馨提醒：您有一个明日档期（" + dateStr + "），请注意时间安排。");
    }

    /** 商家创建抢档期后通知所有关注者（consumer 端 fan-out） */
    public void sendRushCreated(Long merchantId, String merchantNickname, Long scheduleId, String dateStr) {
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType("RUSH_CREATED");
        msg.setMerchantId(merchantId);
        msg.setScheduleId(scheduleId);
        msg.setScheduleDate(dateStr);
        msg.setContent(merchantNickname + " 发布了 " + dateStr + " 的抢档期，快去抢！");
        // destination 格式 "topic:tag"，tag 用于 selectorExpression 过滤
        rocketMQTemplate.syncSend(TOPIC + ":RUSH_CREATED", msg);
        log.info("[MQ] 发送抢档通知 merchantId={} scheduleId={} date={}", merchantId, scheduleId, dateStr);
    }

    /** 抢档期开放前 5 分钟提醒所有关注者（consumer 端 fan-out） */
    public void sendRushReminder(Long merchantId, String merchantNickname, Long scheduleId, String dateStr) {
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType("RUSH_REMINDER");
        msg.setMerchantId(merchantId);
        msg.setScheduleId(scheduleId);
        msg.setScheduleDate(dateStr);
        msg.setContent(merchantNickname + " 的 " + dateStr + " 抢档期将在 5 分钟后开放，快去主页准备抢！");
        rocketMQTemplate.syncSend(TOPIC + ":RUSH_REMINDER", msg);
        log.info("[MQ] 发送抢档倒计时提醒 merchantId={} scheduleId={} date={}", merchantId, scheduleId, dateStr);
    }

    private void send(Long toUserId, Long bookingId, String type, String content) {
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType(type);
        msg.setToUserId(toUserId);
        msg.setBookingId(bookingId);
        msg.setContent(content);
        // destination 格式：topic:tag，tag = 消息类型，便于未来按 tag 做消费过滤
        rocketMQTemplate.syncSend(TOPIC + ":" + type, msg);
        log.info("[MQ] 发送通知 type={} toUser={} bookingId={}", type, toUserId, bookingId);
    }
}
