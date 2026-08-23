package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.BookingCreateDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.BookingMapper;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.ScheduleMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.mq.MQSender;
import com.mhp.booksystem.service.BookingService;
import com.mhp.booksystem.vo.BookingVO;
import com.mhp.booksystem.vo.CursorPageVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl extends ServiceImpl<BookingMapper, Booking> implements BookingService {

    private final ScheduleMapper scheduleMapper;
    private final MerchantMapper merchantMapper;
    private final UserMapper userMapper;
    private final RedissonClient redissonClient;
    private final MQSender mqSender;

    @Override
    @Transactional
    public void create(BookingCreateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();

        Schedule schedule = scheduleMapper.selectById(dto.getScheduleId());
        if (schedule == null) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_FOUND);
        }
        if (schedule.getBookType() != 0) {
            throw new BusinessException(ResultCode.SCHEDULE_NOT_AVAILABLE.getCode(), "该档期为抢档期模式，请通过抢档期预约");
        }

        String lockKey = "booking:create:" + userId + ":" + dto.getScheduleId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked;
        try {
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("预约失败，请重试");
        }
        if (!locked) {
            throw new BusinessException("操作频繁，请稍后再试");
        }

        try {
            schedule = scheduleMapper.selectById(dto.getScheduleId());
            if (schedule.getStatus() != 0) {
                throw new BusinessException(ResultCode.SCHEDULE_NOT_AVAILABLE);
            }

            boolean duplicate = lambdaQuery()
                    .eq(Booking::getUserId, userId)
                    .eq(Booking::getScheduleId, dto.getScheduleId())
                    .ne(Booking::getStatus, 4)
                    .exists();
            if (duplicate) {
                throw new BusinessException(ResultCode.BOOKING_DUPLICATE);
            }

            Booking booking = new Booking();
            booking.setOrderNo(IdUtil.fastSimpleUUID());
            booking.setUserId(userId);
            booking.setMerchantId(schedule.getMerchantId());
            booking.setScheduleId(dto.getScheduleId());
            booking.setStatus(0);
            booking.setRemark(dto.getRemark());
            booking.setQuestionnaireAnswer(dto.getQuestionnaireAnswer());
            booking.setServiceType(schedule.getServiceType());
            save(booking);

            Schedule update = new Schedule();
            update.setId(schedule.getId());
            update.setStatus(1);
            scheduleMapper.updateById(update);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public CursorPageVO<BookingVO> myBookings(Long lastId, int size, Integer serviceType) {
        Long userId = StpUtil.getLoginIdAsLong();
        List<Booking> bookings = lambdaQuery()
                .eq(Booking::getUserId, userId)
                .eq(serviceType != null, Booking::getServiceType, serviceType)
                .lt(lastId != null && lastId > 0, Booking::getId, lastId)
                .orderByDesc(Booking::getId)
                .last("LIMIT " + (size + 1))
                .list();
        return buildCursorPage(bookings, size);
    }

    @Override
    public CursorPageVO<BookingVO> receivedBookings(Long lastId, int size, Integer serviceType, Integer status) {
        Long userId = StpUtil.getLoginIdAsLong();
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId));
        if (merchant == null) {
            return CursorPageVO.of(List.of(), false, null);
        }
        List<Booking> bookings = lambdaQuery()
                .eq(Booking::getMerchantId, merchant.getId())
                .eq(serviceType != null, Booking::getServiceType, serviceType)
                .eq(status != null, Booking::getStatus, status)
                .lt(lastId != null && lastId > 0, Booking::getId, lastId)
                .orderByDesc(Booking::getId)
                .last("LIMIT " + (size + 1))
                .list();
        return buildCursorPage(bookings, size);
    }

    @Override
    public void confirm(Long bookingId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Booking booking = getAndCheckMerchantBooking(bookingId, userId);
        if (booking.getStatus() != 0) {
            throw new BusinessException(ResultCode.BOOKING_STATUS_ERROR);
        }
        updateStatus(bookingId, 2);
        mqSender.sendBookingConfirmed(booking.getUserId(), bookingId);
    }

    @Override
    public void complete(Long bookingId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Booking booking = getAndCheckMerchantBooking(bookingId, userId);
        if (booking.getStatus() != 2) {
            throw new BusinessException(ResultCode.BOOKING_STATUS_ERROR);
        }
        updateStatus(bookingId, 3);
        mqSender.sendBookingCompleted(booking.getUserId(), bookingId);
    }

    @Override
    @Transactional
    public void cancel(Long bookingId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Booking booking = getById(bookingId);
        if (booking == null) {
            throw new BusinessException(ResultCode.BOOKING_NOT_FOUND);
        }

        // 判断操作身份：买家（是这条预约的客人）or 卖家（有对应 Merchant 记录）
        boolean isBuyerCancel = booking.getUserId().equals(userId);
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId));
        boolean isMerchantCancel = merchant != null && booking.getMerchantId().equals(merchant.getId());

        if (!isBuyerCancel && !isMerchantCancel) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        if (booking.getStatus() == 3) {
            throw new BusinessException(ResultCode.BOOKING_STATUS_ERROR.getCode(), "已完成的预约不能取消");
        }
        if (booking.getStatus() == 4) {
            throw new BusinessException(ResultCode.BOOKING_STATUS_ERROR.getCode(), "预约已取消");
        }

        updateStatus(bookingId, 4);

        Schedule update = new Schedule();
        update.setId(booking.getScheduleId());
        update.setStatus(0);
        scheduleMapper.updateById(update);

        if (isMerchantCancel) {
            mqSender.sendBookingCancelled(booking.getUserId(), bookingId, true);
        } else {
            Merchant bookingMerchant = merchantMapper.selectById(booking.getMerchantId());
            if (bookingMerchant != null) {
                mqSender.sendBookingCancelled(bookingMerchant.getUserId(), bookingId, false);
            }
        }
    }

    private Booking getAndCheckMerchantBooking(Long bookingId, Long userId) {
        Booking booking = getById(bookingId);
        if (booking == null) {
            throw new BusinessException(ResultCode.BOOKING_NOT_FOUND);
        }
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId));
        if (merchant == null || !booking.getMerchantId().equals(merchant.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return booking;
    }

    private void updateStatus(Long bookingId, int status) {
        Booking update = new Booking();
        update.setId(bookingId);
        update.setStatus(status);
        updateById(update);
    }

    private CursorPageVO<BookingVO> buildCursorPage(List<Booking> bookings, int size) {
        boolean hasMore = bookings.size() > size;
        if (hasMore) {
            bookings = bookings.subList(0, size);
        }
        if (bookings.isEmpty()) {
            return CursorPageVO.of(List.of(), false, null);
        }

        Set<Long> scheduleIds = bookings.stream().map(Booking::getScheduleId).collect(Collectors.toSet());
        Set<Long> merchantIds = bookings.stream().map(Booking::getMerchantId).collect(Collectors.toSet());
        Set<Long> userIds = bookings.stream().map(Booking::getUserId).collect(Collectors.toSet());

        Map<Long, Schedule> scheduleMap = scheduleMapper.selectBatchIds(scheduleIds).stream()
                .collect(Collectors.toMap(Schedule::getId, s -> s));
        Map<Long, String> merchantNicknameMap = merchantMapper.selectBatchIds(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> {
                    User u = userMapper.selectById(m.getUserId());
                    return u != null ? u.getNickname() : "";
                }));
        Map<Long, String> userNicknameMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));

        List<BookingVO> voList = bookings.stream().map(b -> {
            BookingVO vo = new BookingVO();
            vo.setId(b.getId());
            vo.setOrderNo(b.getOrderNo());
            vo.setMerchantId(b.getMerchantId());
            vo.setUserId(b.getUserId());
            vo.setStatus(b.getStatus());
            vo.setServiceType(b.getServiceType());
            vo.setRemark(b.getRemark());
            vo.setCreateTime(b.getCreateTime());
            Schedule s = scheduleMap.get(b.getScheduleId());
            if (s != null) {
                vo.setScheduleDate(s.getDate());
                vo.setTimeSlot(s.getTimeSlot());
            }
            vo.setMerchantNickname(merchantNicknameMap.get(b.getMerchantId()));
            vo.setUserNickname(userNicknameMap.get(b.getUserId()));
            return vo;
        }).collect(Collectors.toList());

        Long nextCursor = bookings.get(bookings.size() - 1).getId();
        return CursorPageVO.of(voList, hasMore, nextCursor);
    }
}
