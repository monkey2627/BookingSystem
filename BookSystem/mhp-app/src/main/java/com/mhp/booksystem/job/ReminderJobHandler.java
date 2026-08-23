package com.mhp.booksystem.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.mapper.BookingMapper;
import com.mhp.booksystem.mapper.ScheduleMapper;
import com.mhp.booksystem.mq.MQSender;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 档期提醒定时任务。
 *
 * 为什么要发提醒？
 *   Cosplay 拍摄档期通常提前数周预约，客人容易忘记。
 *   每天早上 9 点主动提醒"明天有档期"，减少爽约率，也提升用户体验。
 *
 * 为什么用 MQ 发提醒而不是直接推送？
 *   定时任务扫描出 N 条订单后，如果直接在 Job 里逐条推送（调用短信/WebSocket），
 *   任何一条失败都可能导致后续的都不发。
 *   通过 MQ，Job 只负责"把任务投进队列"，消费者逐条处理，失败的进死信队列，
 *   Job 本身快速完成，推送可靠性由 MQ 保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderJobHandler {

    private final BookingMapper bookingMapper;
    private final ScheduleMapper scheduleMapper;
    private final MQSender mqSender;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM月dd日");

    /**
     * XXL-Job 控制台 Cron 配置：0 0 9 * * ?（每天早上 9:00 触发）
     * 查询明日所有状态=已定档(2) 的订单，向客人投递提醒消息到 MQ。
     */
    @XxlJob("reminderJob")
    public void sendReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 先查明日所有已被预约的档期
        List<Schedule> tomorrowSchedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getDate, tomorrow)
                        .eq(Schedule::getStatus, 1)  // 1=已预约
        );
        if (tomorrowSchedules.isEmpty()) {
            return;
        }

        Map<Long, Schedule> scheduleMap = tomorrowSchedules.stream()
                .collect(Collectors.toMap(Schedule::getId, s -> s));

        // 再查这些档期对应的、状态=已定档(2)的订单
        List<Booking> bookings = bookingMapper.selectList(
                new LambdaQueryWrapper<Booking>()
                        .in(Booking::getScheduleId, scheduleMap.keySet())
                        .eq(Booking::getStatus, 2)
        );

        String dateStr = tomorrow.format(DATE_FMT);
        for (Booking booking : bookings) {
            // 只投递到 MQ，消费者负责实际推送，Job 不阻塞等待推送结果
            mqSender.sendScheduleReminder(booking.getUserId(), booking.getId(), dateStr);
        }

        log.info("[JOB] reminderJob 明日({})发送提醒 {} 条", tomorrow, bookings.size());
    }
}
