package com.mhp.booksystem.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
public class NotifyMessage implements Serializable {

    private String msgId;

    /**
     * 消息类型：
     *   BOOKING_CONFIRMED  — 商家已确认
     *   BOOKING_COMPLETED  — 预约已完成
     *   BOOKING_CANCELLED  — 预约已取消
     *   SCHEDULE_REMINDER  — 明日档期提醒
     *   RUSH_CREATED       — 关注的商家发布了新抢档期（fan-out 给所有关注者）
     *   RUSH_REMINDER      — 抢档期 5 分钟后开放提醒（fan-out 给所有关注者）
     */
    private String type;

    /** 预约/普通通知的目标用户（rush 类型不填，由消费端 fan-out） */
    private Long toUserId;

    private Long bookingId;

    private String content;

    /** 抢档期通知专用：商家 id（消费端查 follow 表做 fan-out） */
    private Long merchantId;

    /** 抢档期通知专用：档期日期（yyyy-MM-dd） */
    private String scheduleDate;

    /** 抢档期通知专用：档期 id */
    private Long scheduleId;
}
