package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.service.MerchantService;
import com.mhp.booksystem.vo.MerchantVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements MerchantService {

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    private static final String CACHE_KEY_PREFIX = "merchant:info:";
    private static final String LOCK_KEY_PREFIX = "lock:merchant:";

    @Override
    public void updateInfo(MerchantUpdateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();

        // saveOrUpdate 逻辑：有商家记录则更新，没有则新建（首次填资料场景）
        Merchant merchant = lambdaQuery()
                .eq(Merchant::getUserId, userId)
                .one();
        if (merchant == null) {
            merchant = new Merchant();
            merchant.setUserId(userId);
        }

        // 只更新非 null 字段，允许商家部分修改（如只改简介不动其他字段）
        if (dto.getServiceTypes() != null) {
            merchant.setServiceTypes(JSONUtil.toJsonStr(dto.getServiceTypes()));
        }
        if (dto.getCity() != null) merchant.setCity(dto.getCity());
        if (dto.getIntro() != null) merchant.setIntro(dto.getIntro());
        if (dto.getAlipayLink() != null) merchant.setAlipayLink(dto.getAlipayLink());
        if (dto.getXianyuLink() != null) merchant.setXianyuLink(dto.getXianyuLink());
        if (dto.getXiaohongshuLink() != null) merchant.setXiaohongshuLink(dto.getXiaohongshuLink());
        if (dto.getWeiboLink() != null) merchant.setWeiboLink(dto.getWeiboLink());
        if (dto.getPriceMin() != null) merchant.setPriceMin(dto.getPriceMin());
        if (dto.getPriceMax() != null) merchant.setPriceMax(dto.getPriceMax());
        if (dto.getBookingNotice() != null) merchant.setBookingNotice(dto.getBookingNotice());

        saveOrUpdate(merchant);
        // Cache Aside 写策略：更新 DB 后删除缓存，下次读时重建，
        // 不直接更新缓存是为了避免并发下缓存和 DB 不一致
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + merchant.getId());
    }

    /**
     * 商家主页缓存，实现"Cache Aside + 防三缓"完整方案。
     *
     * 防缓存穿透：不存在的 merchantId 写空字符串哨兵，TTL 2 分钟。
     *   场景：恶意请求大量不存在的 id，每次都打到 DB。
     *
     * 防缓存击穿：Redisson 分布式锁 + 双重检查（DCL）。
     *   场景：热点商家缓存刚过期，大量请求同时打来，
     *         只让一个请求查 DB，其余等锁释放后读缓存。
     *
     * 防缓存雪崩：TTL 加随机 offset（25~35 分钟）。
     *   场景：大量商家同时缓存，若 TTL 相同会在同一时刻集体过期，
     *         随机 offset 让过期时间错开，避免同时打 DB。
     */
    @Override
    public MerchantVO getDetail(Long merchantId) {
        String cacheKey = CACHE_KEY_PREFIX + merchantId;

        // 第一次读缓存（无锁，最快路径）
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.isEmpty()) {
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND); // 命中穿透哨兵
            }
            return JSONUtil.toBean(cached, MerchantVO.class);
        }

        // 缓存未命中，加分布式锁防击穿
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + merchantId);
        try {
            lock.lock(); // 等待锁（无超时），锁由 finally 释放

            // 双重检查：防止多个线程都过了第一次 get，在锁内再读一次，
            // 避免第一个拿到锁的线程重建缓存后，后续线程重复查 DB
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached.isEmpty()) throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
                return JSONUtil.toBean(cached, MerchantVO.class);
            }

            Merchant merchant = getById(merchantId);
            if (merchant == null) {
                // 写空值哨兵防穿透，短 TTL 避免真实数据出现后太久不可用
                stringRedisTemplate.opsForValue().set(cacheKey, "", 2, TimeUnit.MINUTES);
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            User user = userMapper.selectById(merchant.getUserId());
            MerchantVO vo = toVO(merchant, user);

            // 随机 TTL 25~35 分钟防雪崩
            int ttl = 25 + new Random().nextInt(11);
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(vo), ttl, TimeUnit.MINUTES);
            return vo;

        } finally {
            // 只有当前线程持有锁才解锁，防止因超时等原因锁已被其他线程抢占时误解锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Page<MerchantVO> search(String city, Integer serviceType, String keyword, int page, int size) {
        Page<Merchant> merchantPage = new Page<>(page, size);
        // 自定义 XML SQL：JSON_CONTAINS 搜索 service_types JSON 数组，动态 WHERE 条件
        baseMapper.searchPage(merchantPage, city, serviceType, keyword);

        List<Merchant> records = merchantPage.getRecords();
        if (records.isEmpty()) {
            return new Page<>(page, size, 0);
        }

        // 批量查用户信息（昵称/头像），避免循环内 N 次单查
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
        // 复用带缓存的 getDetail，商家查自己的资料也走缓存
        return getDetail(merchant.getId());
    }

    /** 由 AccountInternalController 暴露给 mhp-social 的 Feign 调用，评价后更新评分 */
    public void updateScore(Long merchantId, java.math.BigDecimal avgScore, Integer reviewCount) {
        Merchant merchant = getById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }
        merchant.setAvgScore(avgScore);
        merchant.setReviewCount(reviewCount);
        updateById(merchant);
        // 评分更新后删除缓存，下次展示时重建
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + merchantId);
    }

    private MerchantVO toVO(Merchant merchant, User user) {
        MerchantVO vo = new MerchantVO();
        vo.setId(merchant.getId());
        vo.setUserId(merchant.getUserId());
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        // serviceTypes 在 DB 存 JSON 字符串，取出时反序列化为 List<Integer>
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
        vo.setPriceMin(merchant.getPriceMin());
        vo.setPriceMax(merchant.getPriceMax());
        vo.setBookingNotice(merchant.getBookingNotice());
        return vo;
    }
}
