package com.mhp.booksystem.service.impl;

import com.mhp.booksystem.security.SecurityUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.BookingCreateDTO;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.rpc.RpcMerchantService;
import com.mhp.booksystem.mapper.BookingMapper;
import com.mhp.booksystem.mapper.ScheduleMapper;
import com.mhp.booksystem.mq.MQSender;
import com.mhp.booksystem.service.BookingService;
import com.mhp.booksystem.vo.BookingVO;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.MerchantStatsVO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboReference;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl extends ServiceImpl<BookingMapper, Booking> implements BookingService {

    private final ScheduleMapper scheduleMapper;
    private final RedissonClient redissonClient;
    private final MQSender mqSender;
    private final AccountFeignClient accountFeignClient;

    @DubboReference(version = "1.0.0")
    private RpcMerchantService rpcMerchantService;

    /**
     * 创建预约 — 核心业务，包含分布式锁防并发重复预约。
     *
     * 加锁原因：
     *   若同一用户同时发起多个对同一档期的预约请求（网络抖动、重复点击），
     *   没有锁时可能同时通过"重复检查"，创建出两条预约记录。
     *   锁的粒度设为 userId+scheduleId，互不干扰。
     *
     * 加锁后为什么要再查一次 schedule？
     *   因为在拿到锁之前的那次查询是无锁的，状态可能在等锁期间被其他线程改变。
     *   拿锁后重查是标准的 DCL（双重检查锁定）模式。
     */
    @Override
    @Transactional
    public void create(BookingCreateDTO dto) {
        Long userId = SecurityUtil.getCurrentUserId();

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
            // 等待 3 秒拿锁，持锁最长 30 秒（防止进程崩溃锁永不释放）
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("预约失败，请重试");
        }
        if (!locked) {
            throw new BusinessException("操作频繁，请稍后再试");
        }

        try {
            // 拿锁后重查，保证状态是最新的
            schedule = scheduleMapper.selectById(dto.getScheduleId());
            if (schedule.getStatus() != 0) {
                throw new BusinessException(ResultCode.SCHEDULE_NOT_AVAILABLE);
            }

            // 幂等性校验：同用户对同档期只能有一条非取消的预约
            boolean duplicate = lambdaQuery()
                    .eq(Booking::getUserId, userId)
                    .eq(Booking::getScheduleId, dto.getScheduleId())
                    .ne(Booking::getStatus, 4) // 排除已取消，取消后允许再次预约
                    .exists();
            if (duplicate) {
                throw new BusinessException(ResultCode.BOOKING_DUPLICATE);
            }

            Booking booking = new Booking();
            booking.setOrderNo(IdUtil.fastSimpleUUID()); // 对外展示的 UUID 订单号
            booking.setUserId(userId);
            booking.setMerchantId(schedule.getMerchantId());
            booking.setScheduleId(dto.getScheduleId());
            booking.setStatus(0); // 初始状态：待确认
            booking.setRemark(dto.getRemark());
            booking.setQuestionnaireAnswer(dto.getQuestionnaireAnswer());
            booking.setServiceType(schedule.getServiceType()); // 从档期冗余服务类型
            save(booking);

            // 档期置为"已预约"，防止其他人再次预约同一档期
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

    /** 客人查"我的预约"，游标分页，支持按服务类型筛选 */
    @Override
    public CursorPageVO<BookingVO> myBookings(Long lastId, int size, Integer serviceType) {
        Long userId = SecurityUtil.getCurrentUserId();
        List<Booking> bookings = lambdaQuery()
                .eq(Booking::getUserId, userId)
                .eq(serviceType != null, Booking::getServiceType, serviceType)
                .lt(lastId != null && lastId > 0, Booking::getId, lastId) // 游标：id 小于上次最后一条
                .orderByDesc(Booking::getId)
                .last("LIMIT " + (size + 1)) // 多查一条判断是否有下一页
                .list();
        return buildCursorPage(bookings, size);
    }

    /** 商家查"收到的预约"，通过 Feign 把 userId 换成 merchantId 再查 */
    @Override
    public CursorPageVO<BookingVO> receivedBookings(Long lastId, int size, Integer serviceType, Integer status) {
        Long userId = SecurityUtil.getCurrentUserId();
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
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

    /** 商家确认预约：待确认(0) → 已定档(2)，并通过 MQ 通知客人 */
    @Override
    public void confirm(Long bookingId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Booking booking = getAndCheckMerchantBooking(bookingId, userId);
        if (booking.getStatus() != 0) {
            throw new BusinessException(ResultCode.BOOKING_STATUS_ERROR);
        }
        updateStatus(bookingId, 2);
        // 通过 RabbitMQ 发通知，mhp-social 消费后推 WebSocket 给客人
        mqSender.sendBookingConfirmed(booking.getUserId(), bookingId);
    }

    /** 商家标记完成：已定档(2) → 已完成(3)，客人此后可以评价 */
    @Override
    public void complete(Long bookingId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Booking booking = getAndCheckMerchantBooking(bookingId, userId);
        if (booking.getStatus() != 2) {
            throw new BusinessException(ResultCode.BOOKING_STATUS_ERROR);
        }
        updateStatus(bookingId, 3);
        mqSender.sendBookingCompleted(booking.getUserId(), bookingId);
    }

    /**
     * 取消预约 — 客人和商家都可以取消，通知对象不同。
     *
     * 客人取消：通知商家（把通知发给 bookingMerchant.userId）
     * 商家取消：通知客人（把通知发给 booking.userId）
     */
    @Override
    @Transactional
    public void cancel(Long bookingId) {
        Long userId = SecurityUtil.getCurrentUserId();
        Booking booking = getById(bookingId);
        if (booking == null) {
            throw new BusinessException(ResultCode.BOOKING_NOT_FOUND);
        }

        boolean isBuyerCancel = booking.getUserId().equals(userId);
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
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

        // 档期释放回"空闲"，允许其他人再次预约
        Schedule update = new Schedule();
        update.setId(booking.getScheduleId());
        update.setStatus(0);
        scheduleMapper.updateById(update);

        if (isMerchantCancel) {
            mqSender.sendBookingCancelled(booking.getUserId(), bookingId, true);
        } else {
            // 客人取消，需要通知商家，通过 merchantId 换取商家的 userId
            MerchantDTO bookingMerchant = accountFeignClient.getMerchant(booking.getMerchantId()).getData();
            if (bookingMerchant != null) {
                mqSender.sendBookingCancelled(bookingMerchant.getUserId(), bookingId, false);
            }
        }
    }

    /** 商家数据看板：本月接单数、完成数、取消数、待确认数，以及评分 */
    @Override
    public MerchantStatsVO getMerchantStats() {
        Long userId = SecurityUtil.getCurrentUserId();
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }

        LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIDNIGHT);

        MerchantStatsVO stats = new MerchantStatsVO();
        stats.setTotalOrders(baseMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)).intValue());
        stats.setCompletedOrders(baseMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)
                        .eq(Booking::getStatus, 3)).intValue());
        stats.setCancelledOrders(baseMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)
                        .eq(Booking::getStatus, 4)).intValue());
        stats.setPendingOrders(baseMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)
                        .eq(Booking::getStatus, 0)).intValue());
        // 评分直接用冗余字段，无需聚合 review 表
        stats.setAvgScore(merchant.getAvgScore());
        stats.setReviewCount(merchant.getReviewCount());
        return stats;
    }

    /** 校验当前用户是该预约的商家方，返回 Booking 供后续状态判断 */
    private Booking getAndCheckMerchantBooking(Long bookingId, Long userId) {
        Booking booking = getById(bookingId);
        if (booking == null) {
            throw new BusinessException(ResultCode.BOOKING_NOT_FOUND);
        }
        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
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

    /**
     * 游标分页构建器 — 多查 1 条来判断是否有下一页，不用 COUNT(*) 查总数。
     *
     * 为什么用游标而不是 offset 分页？
     *   offset 分页在深翻页时性能差（LIMIT 1000000, 10 需要扫描并丢弃前 100 万行）。
     *   游标分页通过 WHERE id < lastId 直接跳到目标位置，性能稳定。
     *   代价是无法跳页，只能"上一页/下一页"，适合移动端上拉加载场景。
     *
     * 附带 Feign 批量查用户和商家信息，构建完整的 BookingVO 列表。
     */
    private CursorPageVO<BookingVO> buildCursorPage(List<Booking> bookings, int size) {
        boolean hasMore = bookings.size() > size;
        if (hasMore) {
            bookings = bookings.subList(0, size);
        }
        if (bookings.isEmpty()) {
            return CursorPageVO.of(List.of(), false, null);
        }

        Set<Long> scheduleIds = bookings.stream().map(Booking::getScheduleId).collect(Collectors.toSet());
        List<Long> merchantIdList = bookings.stream().map(Booking::getMerchantId).distinct().collect(Collectors.toList());
        List<Long> userIdList = bookings.stream().map(Booking::getUserId).distinct().collect(Collectors.toList());

        Map<Long, Schedule> scheduleMap = scheduleMapper.selectBatchIds(scheduleIds).stream()
                .collect(Collectors.toMap(Schedule::getId, s -> s));

        // 批量查商家，再批量查商家对应的用户（昵称），两次 Feign 而非 N 次
        List<MerchantDTO> merchants = accountFeignClient.batchGetMerchants(merchantIdList).getData();
        List<Long> merchantUserIds = merchants.stream().map(MerchantDTO::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> merchantUsers = accountFeignClient.batchGetUsers(merchantUserIds).getData();
        Map<Long, String> merchantUserNicknameMap = merchantUsers.stream()
                .collect(Collectors.toMap(UserDTO::getId, u -> u.getNickname() != null ? u.getNickname() : ""));
        Map<Long, String> merchantNicknameMap = merchants.stream()
                .collect(Collectors.toMap(MerchantDTO::getId,
                        m -> merchantUserNicknameMap.getOrDefault(m.getUserId(), "")));
        Map<Long, Long> merchantIdToUserIdMap = merchants.stream()
                .collect(Collectors.toMap(MerchantDTO::getId, MerchantDTO::getUserId));

        List<UserDTO> bookingUsers = accountFeignClient.batchGetUsers(new ArrayList<>(userIdList)).getData();
        Map<Long, String> userNicknameMap = bookingUsers.stream()
                .collect(Collectors.toMap(UserDTO::getId, u -> u.getNickname() != null ? u.getNickname() : ""));

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
            vo.setMerchantUserId(merchantIdToUserIdMap.get(b.getMerchantId()));
            vo.setMerchantNickname(merchantNicknameMap.get(b.getMerchantId()));
            vo.setUserNickname(userNicknameMap.get(b.getUserId()));
            return vo;
        }).collect(Collectors.toList());

        Long nextCursor = bookings.get(bookings.size() - 1).getId();
        return CursorPageVO.of(voList, hasMore, nextCursor);
    }
}
