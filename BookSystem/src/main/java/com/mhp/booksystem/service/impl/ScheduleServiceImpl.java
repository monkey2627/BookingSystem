package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.BatchScheduleDTO;
import com.mhp.booksystem.dto.ScheduleCreateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.RushRecord;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.RushRecordMapper;
import com.mhp.booksystem.mapper.ScheduleMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.service.ScheduleService;
import com.mhp.booksystem.vo.RushRecordVO;
import com.mhp.booksystem.vo.RushResultVO;
import com.mhp.booksystem.vo.ScheduleVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl extends ServiceImpl<ScheduleMapper, Schedule> implements ScheduleService {

    private final UserMapper userMapper;
    private final MerchantMapper merchantMapper;
    private final RushRecordMapper rushRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> rushScript;

    @PostConstruct
    public void initScript() {
        rushScript = new DefaultRedisScript<>();
        rushScript.setLocation(new ClassPathResource("lua/rush.lua"));
        rushScript.setResultType(Long.class);
    }

    @Override
    public void create(ScheduleCreateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();

        // 通过 userId 查商家 id
        var merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.mhp.booksystem.entity.Merchant>()
                        .eq(com.mhp.booksystem.entity.Merchant::getUserId, userId));
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }

        // 同一商家同一天同一时间段不能重复
        boolean duplicate = lambdaQuery()
                .eq(Schedule::getMerchantId, merchant.getId())
                .eq(Schedule::getDate, dto.getDate())
                .eq(StringUtils.hasText(dto.getTimeSlot()), Schedule::getTimeSlot, dto.getTimeSlot())
                .exists();
        if (duplicate) {
            throw new BusinessException(ResultCode.SCHEDULE_DUPLICATE);
        }

        // 抢档期模式必须填开放时间
        if (dto.getBookType() == 1 && dto.getRushOpenTime() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "抢档期模式必须设置开放时间");
        }

        Schedule schedule = new Schedule();
        schedule.setMerchantId(merchant.getId());
        schedule.setDate(dto.getDate());
        schedule.setTimeSlot(dto.getTimeSlot());
        schedule.setStatus(0);
        schedule.setBookType(dto.getBookType());
        schedule.setServiceType(dto.getServiceType());
        schedule.setRushOpenTime(dto.getRushOpenTime());
        schedule.setMaxQueueSize(dto.getMaxQueueSize() != null ? dto.getMaxQueueSize() : 10);
        save(schedule);
    }

    @Override
    public List<ScheduleVO> listByMonth(Long merchantId, String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "月份格式错误，应为 yyyy-MM");
        }

        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<Schedule> schedules = lambdaQuery()
                .eq(Schedule::getMerchantId, merchantId)
                .between(Schedule::getDate, start, end)
                .orderByAsc(Schedule::getDate)
                .list();

        // 批量查各档期的排队人数
        return schedules.stream().map(s -> {
            ScheduleVO vo = toVO(s);
            if (s.getBookType() == 1) {
                long count = rushRecordMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RushRecord>()
                                .eq(RushRecord::getScheduleId, s.getId())
                                .eq(RushRecord::getStatus, 0));
                vo.setCurrentQueueSize((int) count);
            }
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public RushResultVO rush(Long scheduleId) {
        Long userId = StpUtil.getLoginIdAsLong();

        Schedule schedule = getById(scheduleId);
        if (schedule == null) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_FOUND);
        }
        if (schedule.getBookType() != 1) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_AVAILABLE.getCode(), "该档期不是抢档期模式");
        }
        if (schedule.getStatus() != 0) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_AVAILABLE);
        }
        if (schedule.getRushOpenTime() != null && LocalDateTime.now().isBefore(schedule.getRushOpenTime())) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_OPEN);
        }

        // Lua 脚本原子执行：检查重复 + 检查队满 + 入队，三步合一不可分割
        // 原因：非原子的"查-判-写"在高并发下会出现多个线程同时通过检查后一起写入，
        // 导致超出 maxQueueSize 或同一用户入队两次
        String key = "schedule:" + scheduleId;
        Long result = stringRedisTemplate.execute(rushScript,
                Collections.singletonList(key),
                String.valueOf(userId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(schedule.getMaxQueueSize()));

        if (result == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "系统繁忙，请稍后重试");
        }
        if (result == -1L) {
            throw new BusinessException(ResultCode.RUSH_ALREADY_JOINED);
        }
        if (result == 0L) {
            throw new BusinessException(ResultCode.SCHEDULE_FULL);
        }

        int rankNo = result.intValue();

        // Lua 脚本写入 Redis ZSET 成功后，持久化到 DB
        RushRecord record = new RushRecord();
        record.setScheduleId(scheduleId);
        record.setUserId(userId);
        record.setRankNo(rankNo);
        record.setStatus(0);
        record.setRushTime(LocalDateTime.now());
        rushRecordMapper.insert(record);

        RushResultVO vo = new RushResultVO();
        vo.setRankNo(rankNo);
        vo.setMessage("您已排在第 " + rankNo + " 位，商家会依序联系您");
        return vo;
    }

    // ==================== 新增方法 ====================

    @Override
    public void batchCreate(BatchScheduleDTO dto) {
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "开始日期不能晚于结束日期");
        }

        Long userId = StpUtil.getLoginIdAsLong();

        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }

        // 抢档期模式必须填开放时间
        if (dto.getBookType() == 1 && dto.getRushOpenTime() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "抢档期模式必须设置开放时间");
        }

        // 遍历日期范围，过滤符合 weekdays 的日期
        List<LocalDate> candidates = new ArrayList<>();
        LocalDate cursor = dto.getStartDate();
        while (!cursor.isAfter(dto.getEndDate())) {
            // Java DayOfWeek.getValue(): MONDAY=1 ... SUNDAY=7，与 weekdays 定义一致
            int dayOfWeek = cursor.getDayOfWeek().getValue();
            if (dto.getWeekdays() == null || dto.getWeekdays().isEmpty()
                    || dto.getWeekdays().contains(dayOfWeek)) {
                candidates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }

        for (LocalDate date : candidates) {
            // 跳过已存在的日期，不报错
            boolean duplicate = lambdaQuery()
                    .eq(Schedule::getMerchantId, merchant.getId())
                    .eq(Schedule::getDate, date)
                    .eq(StringUtils.hasText(dto.getTimeSlot()), Schedule::getTimeSlot, dto.getTimeSlot())
                    .exists();
            if (duplicate) {
                continue;
            }

            Schedule schedule = new Schedule();
            schedule.setMerchantId(merchant.getId());
            schedule.setDate(date);
            schedule.setTimeSlot(dto.getTimeSlot());
            schedule.setStatus(0);
            schedule.setBookType(dto.getBookType());
            schedule.setServiceType(dto.getServiceType());
            schedule.setRushOpenTime(dto.getRushOpenTime());
            schedule.setMaxQueueSize(dto.getMaxQueueSize() != null ? dto.getMaxQueueSize() : 10);
            save(schedule);
        }
    }

    @Override
    public void deleteSchedule(Long scheduleId) {
        Long userId = StpUtil.getLoginIdAsLong();

        // 校验档期存在
        Schedule schedule = getById(scheduleId);
        if (schedule == null) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_FOUND);
        }

        // 校验商家归属：通过 userId 找到商家，再比对 merchantId
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null || !merchant.getId().equals(schedule.getMerchantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限删除该档期");
        }

        // 仅空闲状态可删除
        if (schedule.getStatus() != 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "非空闲档期不能删除");
        }

        removeById(scheduleId);
    }

    @Override
    public List<RushRecordVO> getQueue(Long scheduleId) {
        // 查该档期的所有排队记录，按 rankNo 升序
        List<RushRecord> records = rushRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RushRecord>()
                        .eq(RushRecord::getScheduleId, scheduleId)
                        .orderByAsc(RushRecord::getRankNo));

        if (records.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查用户昵称
        List<Long> userIds = records.stream()
                .map(RushRecord::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return records.stream().map(r -> {
            RushRecordVO vo = new RushRecordVO();
            vo.setId(r.getId());
            vo.setScheduleId(r.getScheduleId());
            vo.setUserId(r.getUserId());
            User u = userMap.get(r.getUserId());
            vo.setUserNickname(u != null ? u.getNickname() : null);
            vo.setRankNo(r.getRankNo());
            vo.setStatus(r.getStatus());
            vo.setRushTime(r.getRushTime());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public void updateRushStatus(Long rushId, Integer status) {
        Long userId = StpUtil.getLoginIdAsLong();

        RushRecord record = rushRecordMapper.selectById(rushId);
        if (record == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "排队记录不存在");
        }

        // 校验归属：通过 schedule → merchant → userId 验证操作者是该档期的商家
        Schedule schedule = getById(record.getScheduleId());
        if (schedule == null) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_FOUND);
        }
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null || !merchant.getId().equals(schedule.getMerchantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限修改该排队记录");
        }

        record.setStatus(status);
        rushRecordMapper.updateById(record);
    }

    private ScheduleVO toVO(Schedule s) {
        ScheduleVO vo = new ScheduleVO();
        vo.setId(s.getId());
        vo.setMerchantId(s.getMerchantId());
        vo.setDate(s.getDate());
        vo.setTimeSlot(s.getTimeSlot());
        vo.setStatus(s.getStatus());
        vo.setBookType(s.getBookType());
        vo.setServiceType(s.getServiceType());
        vo.setRushOpenTime(s.getRushOpenTime());
        vo.setMaxQueueSize(s.getMaxQueueSize());
        return vo;
    }
}
