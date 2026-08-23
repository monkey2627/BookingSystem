package com.mhp.booksystem.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.mapper.BookingMapper;
import com.mhp.booksystem.mapper.ScheduleMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingJobHandler {

    private final BookingMapper bookingMapper;
    private final ScheduleMapper scheduleMapper;

    /**
     * XXL-Job 控制台 Cron 配置：0 * * * * ?（每分钟整点触发）
     * 扫描状态=待付款(1) 且创建时间超过 30 分钟的预约，批量取消并释放档期。
     */
    @XxlJob("cancelTimeoutBookingJob")
    public void cancelTimeoutBookings() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(30);

        List<Booking> timeoutBookings = bookingMapper.selectList(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getStatus, 1)
                        .lt(Booking::getCreateTime, deadline)
                        .last("LIMIT 100")
        );

        if (timeoutBookings.isEmpty()) {
            return;
        }

        for (Booking booking : timeoutBookings) {
            Booking cancel = new Booking();
            cancel.setId(booking.getId());
            cancel.setStatus(4);
            bookingMapper.updateById(cancel);

            Schedule release = new Schedule();
            release.setId(booking.getScheduleId());
            release.setStatus(0);
            scheduleMapper.updateById(release);
        }

        log.info("[JOB] cancelTimeoutBookings 取消超时预约 {} 条", timeoutBookings.size());
    }
}
