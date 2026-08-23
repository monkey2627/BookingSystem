package com.mhp.booksystem.mq;

import cn.hutool.core.util.IdUtil;
import com.mhp.booksystem.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MQSender {

    private final RabbitTemplate rabbitTemplate;

    public void sendBookingConfirmed(Long toUserId, Long bookingId) {
        send(toUserId, bookingId, "BOOKING_CONFIRMED", "您的预约已被商家确认，请按时赴约！");
    }

    public void sendBookingCompleted(Long toUserId, Long bookingId) {
        send(toUserId, bookingId, "BOOKING_COMPLETED", "预约已完成，欢迎留下评价~");
    }

    public void sendBookingCancelled(Long toUserId, Long bookingId, boolean isMerchantCancel) {
        String content = isMerchantCancel ? "商家已取消本次预约，如有疑问请联系商家。" : "您的预约已取消。";
        send(toUserId, bookingId, "BOOKING_CANCELLED", content);
    }

    public void sendScheduleReminder(Long toUserId, Long bookingId, String dateStr) {
        send(toUserId, bookingId, "SCHEDULE_REMINDER", "温馨提醒：您有一个明日档期（" + dateStr + "），请注意时间安排。");
    }

    private void send(Long toUserId, Long bookingId, String type, String content) {
        NotifyMessage msg = new NotifyMessage(IdUtil.fastSimpleUUID(), type, toUserId, bookingId, content);
        String routingKey = "notify." + type.toLowerCase();
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, msg);
        log.info("[MQ] 发送通知 type={} toUser={} bookingId={}", type, toUserId, bookingId);
    }
}
