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

/**
 * 预约超时取消定时任务（XXL-Job）。
 *
 * 注册名：cancelTimeoutBookingJob（需在 XXL-Job 控制台 localhost:8080/xxl-job-admin 新建同名任务）
 * 建议 Cron：0 *\/5 * * * ?（每 5 分钟执行一次）
 *
 * 逻辑：查找 status=1（待付款）且超过 30 分钟未付款的预约，强制取消并释放档期。
 *
 * 注意：status=1（待付款）当前未实际使用（无支付集成，confirm() 直接跳 status=2）。
 *       此 Job 是为将来接入支付宝/微信支付预留的，目前运行不会找到任何记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingJobHandler {

    private final BookingMapper bookingMapper;
    private final ScheduleMapper scheduleMapper;

    @XxlJob("cancelTimeoutBookingJob")
    public void cancelTimeoutBookings() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(30);

        // LIMIT 100：单次处理上限，防止大批量更新导致数据库压力峰值
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
            // 取消预约
            Booking cancel = new Booking();
            cancel.setId(booking.getId());
            cancel.setStatus(4);
            bookingMapper.updateById(cancel);

            // 释放对应档期回"空闲"状态
            Schedule release = new Schedule();
            release.setId(booking.getScheduleId());
            release.setStatus(0);
            scheduleMapper.updateById(release);
        }

        log.info("[JOB] cancelTimeoutBookings 取消超时预约 {} 条", timeoutBookings.size());
    }
}
