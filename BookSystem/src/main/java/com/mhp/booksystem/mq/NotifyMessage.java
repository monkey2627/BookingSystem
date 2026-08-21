package com.mhp.booksystem.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyMessage implements Serializable {

    private String msgId;

    /**
     * 消息类型：
     *   BOOKING_CONFIRMED  — 商家已确认
     *   BOOKING_COMPLETED  — 预约已完成
     *   BOOKING_CANCELLED  — 预约已取消
     *   SCHEDULE_REMINDER  — 明日档期提醒
     */
    private String type;

    private Long toUserId;

    private Long bookingId;

    private String content;
}
