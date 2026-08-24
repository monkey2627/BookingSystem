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
 * 每日档期提醒定时任务（XXL-Job）。
 *
 * 注册名：reminderJob（需在 XXL-Job 控制台新建同名任务）
 * 建议 Cron：0 0 20 * * ?（每天晚 8 点提醒明日有档期的客人）
 *
 * 逻辑：查明天所有 status=1（已预约）的档期 → 找对应 status=2（已定档）的预约
 *       → 给每个客人通过 MQ 发提醒通知 → mhp-social 消费后推 WebSocket。
 *
 * 为什么不直接查 booking 表 join schedule 表？
 *   微服务内部可以跨 mapper 查询（同一个服务），但尽量避免跨表大 JOIN，
 *   先查档期再 IN 查预约，逻辑清晰且便于后续缓存优化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderJobHandler {

    private final BookingMapper bookingMapper;
    private final ScheduleMapper scheduleMapper;
    private final MQSender mqSender;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM月dd日");

    @XxlJob("reminderJob")
    public void sendReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 查明天所有已被预约的档期（status=1）
        List<Schedule> tomorrowSchedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getDate, tomorrow)
                        .eq(Schedule::getStatus, 1)
        );
        if (tomorrowSchedules.isEmpty()) {
            return;
        }

        Map<Long, Schedule> scheduleMap = tomorrowSchedules.stream()
                .collect(Collectors.toMap(Schedule::getId, s -> s));

        // 查这些档期对应的已定档预约（status=2），只提醒已确认的客人
        List<Booking> bookings = bookingMapper.selectList(
                new LambdaQueryWrapper<Booking>()
                        .in(Booking::getScheduleId, scheduleMap.keySet())
                        .eq(Booking::getStatus, 2)
        );

        String dateStr = tomorrow.format(DATE_FMT);
        for (Booking booking : bookings) {
            mqSender.sendScheduleReminder(booking.getUserId(), booking.getId(), dateStr);
        }

        log.info("[JOB] reminderJob 明日({})发送提醒 {} 条", tomorrow, bookings.size());
    }
}
