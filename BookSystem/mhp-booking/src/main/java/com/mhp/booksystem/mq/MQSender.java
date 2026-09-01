package com.mhp.booksystem.mq;

import cn.hutool.core.util.IdUtil;
import com.mhp.booksystem.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 预约事件消息发送器 — mhp-booking 生产消息，mhp-social 消费后推 WebSocket。
 *
 * 消息流向：
 *   BookingServiceImpl/ReminderJobHandler
 *     → MQSender.send()
 *       → RabbitMQ Topic 交换机 "schedule.exchange"
 *         → routing key "notify.{type_lowercase}"  匹配  "notify.#"
 *           → notify.queue
 *             → mhp-social NotifyConsumer
 *               → WebSocket /user/queue/notify → 浏览器
 *
 * 为什么用 MQ 而不是直接 Feign 调 social 推 WebSocket？
 *   1. 解耦：booking 不依赖 social 服务是否在线，消息可以积压等 social 恢复。
 *   2. 可靠性：MQ 持久化，booking 发出后即使 social 宕机，重启后仍能消费。
 *   3. 异步：booking 不需要等通知发送完成才返回响应给客人。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MQSender {

    private final RabbitTemplate rabbitTemplate;

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
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "notify.rush_created", msg);
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
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "notify.rush_reminder", msg);
        log.info("[MQ] 发送抢档倒计时提醒 merchantId={} scheduleId={} date={}", merchantId, scheduleId, dateStr);
    }

    private void send(Long toUserId, Long bookingId, String type, String content) {
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType(type);
        msg.setToUserId(toUserId);
        msg.setBookingId(bookingId);
        msg.setContent(content);
        // routing key 格式：notify.booking_confirmed / notify.booking_cancelled 等
        // 交换机的绑定 key 是 "notify.#"，# 通配任意词，全部路由到 notify.queue
        String routingKey = "notify." + type.toLowerCase();
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, msg);
        log.info("[MQ] 发送通知 type={} toUser={} bookingId={}", type, toUserId, bookingId);
    }
}
