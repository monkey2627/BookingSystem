package com.mhp.booksystem.mq;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MQSender {

    private static final String TOPIC = "mhp-notify-topic";

    private final RocketMQTemplate rocketMQTemplate;

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
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType(type);
        msg.setToUserId(toUserId);
        msg.setBookingId(bookingId);
        msg.setContent(content);
        rocketMQTemplate.syncSend(TOPIC + ":" + type, msg);
        log.info("[MQ] 发送通知 type={} toUser={} bookingId={}", type, toUserId, bookingId);
    }
}
