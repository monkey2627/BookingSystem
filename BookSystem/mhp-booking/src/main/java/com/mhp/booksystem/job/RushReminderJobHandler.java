package com.mhp.booksystem.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.mapper.ScheduleMapper;
import com.mhp.booksystem.mq.MQSender;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抢档期开放前 5 分钟提醒定时任务（XXL-Job）。
 *
 * 注册名：rushReminderJob（需在 XXL-Job 控制台新建同名任务）
 * 建议 Cron：0/1 * * * * ?（每分钟扫描一次）
 *
 * 逻辑：扫描 rushOpenTime 在 [now, now+6min] 内的抢档期
 *       → Redis SET NX 去重（同一档期只提醒一次）
 *       → 查商家昵称 → 发 MQ → social 消费后 fan-out 给关注者。
 *
 * 为什么扫 6 分钟窗口？
 *   任务每分钟执行一次，若某次执行延迟，下次可以补扫。
 *   Redis NX 确保同一档期不会重复发送提醒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RushReminderJobHandler {

    private final ScheduleMapper scheduleMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final MQSender mqSender;
    private final AccountFeignClient accountFeignClient;

    @XxlJob("rushReminderJob")
    public void sendRushReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusMinutes(6);

        List<Schedule> upcoming = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getBookType, 1)
                        .eq(Schedule::getStatus, 0)
                        .between(Schedule::getRushOpenTime, now, windowEnd)
        );

        int sent = 0;
        for (Schedule schedule : upcoming) {
            String dedupKey = "rush:reminder:" + schedule.getId();
            Boolean isNew = stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", 10, TimeUnit.MINUTES);
            if (Boolean.FALSE.equals(isNew)) {
                continue;
            }

            MerchantDTO merchant = accountFeignClient.getMerchant(schedule.getMerchantId()).getData();
            if (merchant == null) {
                continue;
            }
            UserDTO user = accountFeignClient.getUser(merchant.getUserId()).getData();
            String nickname = (user != null && user.getNickname() != null) ? user.getNickname() : "商家";

            mqSender.sendRushReminder(schedule.getMerchantId(), nickname, schedule.getId(),
                    schedule.getDate().toString());
            sent++;
        }

        if (sent > 0) {
            log.info("[JOB] rushReminderJob 发送即将开放提醒 {} 条", sent);
        }
    }
}
