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
