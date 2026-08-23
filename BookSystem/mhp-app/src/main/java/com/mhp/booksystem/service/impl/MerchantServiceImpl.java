package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.BookingMapper;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.service.MerchantService;
import com.mhp.booksystem.vo.MerchantStatsVO;
import com.mhp.booksystem.vo.MerchantVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 为什么 @Service 加在实现类而不是接口？
 *
 * @Service 本质是 @Component 的别名，作用是让 Spring 把这个类实例化并纳入容器管理。
 * 接口不能实例化，所以注解必须加在实现类上。
 *
 * @Service 相比 @Component 多出的价值：
 *   1. 语义分层：明确表示这是 Service 层，区别于 @Controller（表现层）和 @Repository（数据层）
 *   2. AOP 切点：可以写切面专门拦截所有 @Service 类，做统一的事务、日志、耗时统计，不误伤其他层
 *   3. 对比 @Repository：@Repository 除了标注还会把数据库底层异常统一转成 Spring 的
 *      DataAccessException，方便上层统一处理；@Service 没有这个额外功能
 *
 * 为什么继承 ServiceImpl<MerchantMapper, Merchant>？
 *
 * ServiceImpl 是 MyBatis-Plus 对 IService 的默认实现，内部持有 Mapper 引用，
 * 实现了所有通用 CRUD 方法（getById/save/list/page 等）。
 * 继承它之后，这些方法直接可用，不需要自己注入 Mapper 再手写。
 */
@Service
@RequiredArgsConstructor
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements MerchantService {

    private final UserMapper userMapper;
    private final BookingMapper bookingMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    private static final String CACHE_KEY_PREFIX = "merchant:info:";
    private static final String LOCK_KEY_PREFIX = "lock:merchant:";

    @Override
    public void updateInfo(MerchantUpdateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();

        // 任何登录用户均可开通/更新卖家资料，查已有记录则更新，否则新建
        Merchant merchant = lambdaQuery()
                .eq(Merchant::getUserId, userId)
                .one();
        if (merchant == null) {
            merchant = new Merchant();
            merchant.setUserId(userId);
        }

        if (dto.getServiceTypes() != null) {
            merchant.setServiceTypes(JSONUtil.toJsonStr(dto.getServiceTypes()));
        }
        if (dto.getCity() != null) {
            merchant.setCity(dto.getCity());
        }
        if (dto.getIntro() != null) {
            merchant.setIntro(dto.getIntro());
        }
        if (dto.getAlipayLink() != null) {
            merchant.setAlipayLink(dto.getAlipayLink());
        }
        if (dto.getXianyuLink() != null) {
            merchant.setXianyuLink(dto.getXianyuLink());
        }
        if (dto.getXiaohongshuLink() != null) {
            merchant.setXiaohongshuLink(dto.getXiaohongshuLink());
        }
        if (dto.getWeiboLink() != null) {
            merchant.setWeiboLink(dto.getWeiboLink());
        }

        saveOrUpdate(merchant);
        // Cache Aside 写策略：更新 DB 后删缓存，下次读时重建，而不是直接更新缓存
        // 原因：更新缓存在并发写时可能导致旧数据覆盖新数据（写-写竞争）
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + merchant.getId());
    }

    @Override
    public MerchantVO getDetail(Long merchantId) {
        String cacheKey = CACHE_KEY_PREFIX + merchantId;

        // 1. 查缓存
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.isEmpty()) {
                // 空值哨兵命中：该 ID 对应的商家在 DB 中不存在（防穿透）
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            return JSONUtil.toBean(cached, MerchantVO.class);
        }

        // 2. 缓存未命中，加 Redisson 锁防击穿
        // 场景：热点商家缓存突然过期，大量请求同时 miss，
        // 没有锁的话全部打到 DB，有锁则只有一个线程去查 DB，其余等待后复用结果
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + merchantId);
        try {
            lock.lock();

            // 3. 双重检查：拿到锁后再查一次缓存
            // 原因：在等锁期间，持锁线程可能已经写入了缓存
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached.isEmpty()) {
                    throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
                }
                return JSONUtil.toBean(cached, MerchantVO.class);
            }

            // 4. 查数据库
            Merchant merchant = getById(merchantId);
            if (merchant == null) {
                // 空值哨兵防穿透：对不存在的 ID 也缓存一个空标记，TTL 设短（2 分钟）
                // 防止攻击者用不存在的 ID 不断请求，绕过缓存直接打 DB
                stringRedisTemplate.opsForValue().set(cacheKey, "", 2, TimeUnit.MINUTES);
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            User user = userMapper.selectById(merchant.getUserId());
            MerchantVO vo = toVO(merchant, user);

            // 5. 写缓存，TTL 随机 25~35 分钟防雪崩
            // 雪崩场景：所有缓存同时用固定 TTL，到期时大批 key 同时失效，请求全部打到 DB
            // 加随机偏移后，过期时间分散，避免同一时刻大量 miss
            int ttl = 25 + new Random().nextInt(11);
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(vo), ttl, TimeUnit.MINUTES);
            return vo;

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Page<MerchantVO> search(String city, Integer serviceType, String keyword, int page, int size) {
        Page<Merchant> merchantPage = new Page<>(page, size);
        baseMapper.searchPage(merchantPage, city, serviceType, keyword);

        List<Merchant> records = merchantPage.getRecords();
        if (records.isEmpty()) {
            return new Page<>(page, size, 0);
        }

        // 批量查用户，避免 N+1 查询
        List<Long> userIds = records.stream().map(Merchant::getUserId).collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<MerchantVO> voList = records.stream()
                .map(m -> toVO(m, userMap.get(m.getUserId())))
                .collect(Collectors.toList());

        Page<MerchantVO> result = new Page<>(merchantPage.getCurrent(), merchantPage.getSize(), merchantPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public MerchantVO getMyInfo() {
        Long userId = StpUtil.getLoginIdAsLong();
        Merchant merchant = lambdaQuery().eq(Merchant::getUserId, userId).one();
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }
        return getDetail(merchant.getId());
    }

    @Override
    public MerchantStatsVO getStats() {
        Long userId = StpUtil.getLoginIdAsLong();
        Merchant merchant = lambdaQuery().eq(Merchant::getUserId, userId).one();
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }

        LocalDateTime monthStart = LocalDateTime.of(
                LocalDate.now().withDayOfMonth(1), LocalTime.MIDNIGHT);

        LambdaQueryWrapper<Booking> base = new LambdaQueryWrapper<Booking>()
                .eq(Booking::getMerchantId, merchant.getId())
                .ge(Booking::getCreateTime, monthStart);

        MerchantStatsVO stats = new MerchantStatsVO();
        stats.setTotalOrders(bookingMapper.selectCount(base).intValue());
        stats.setCompletedOrders(bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)
                        .eq(Booking::getStatus, 3)).intValue());
        stats.setCancelledOrders(bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)
                        .eq(Booking::getStatus, 4)).intValue());
        stats.setPendingOrders(bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getMerchantId, merchant.getId())
                        .ge(Booking::getCreateTime, monthStart)
                        .eq(Booking::getStatus, 0)).intValue());
        stats.setAvgScore(merchant.getAvgScore());
        stats.setReviewCount(merchant.getReviewCount());
        return stats;
    }

    private MerchantVO toVO(Merchant merchant, User user) {
        MerchantVO vo = new MerchantVO();
        vo.setId(merchant.getId());
        vo.setUserId(merchant.getUserId());
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        if (StringUtils.hasText(merchant.getServiceTypes())) {
            vo.setServiceTypes(JSONUtil.toList(JSONUtil.parseArray(merchant.getServiceTypes()), Integer.class));
        } else {
            vo.setServiceTypes(Collections.emptyList());
        }
        vo.setCity(merchant.getCity());
        vo.setIntro(merchant.getIntro());
        vo.setAlipayLink(merchant.getAlipayLink());
        vo.setXianyuLink(merchant.getXianyuLink());
        vo.setXiaohongshuLink(merchant.getXiaohongshuLink());
        vo.setWeiboLink(merchant.getWeiboLink());
        vo.setAvgScore(merchant.getAvgScore());
        vo.setReviewCount(merchant.getReviewCount());
        return vo;
    }
}
