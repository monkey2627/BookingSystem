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
        if (dto.getPriceMin() != null) {
            merchant.setPriceMin(dto.getPriceMin());
        }
        if (dto.getPriceMax() != null) {
            merchant.setPriceMax(dto.getPriceMax());
        }
        if (dto.getBookingNotice() != null) {
            merchant.setBookingNotice(dto.getBookingNotice());
        }

        saveOrUpdate(merchant);
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + merchant.getId());
    }

    @Override
    public MerchantVO getDetail(Long merchantId) {
        String cacheKey = CACHE_KEY_PREFIX + merchantId;

        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.isEmpty()) {
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            return JSONUtil.toBean(cached, MerchantVO.class);
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + merchantId);
        try {
            lock.lock();

            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached.isEmpty()) {
                    throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
                }
                return JSONUtil.toBean(cached, MerchantVO.class);
            }

            Merchant merchant = getById(merchantId);
            if (merchant == null) {
                stringRedisTemplate.opsForValue().set(cacheKey, "", 2, TimeUnit.MINUTES);
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            User user = userMapper.selectById(merchant.getUserId());
            MerchantVO vo = toVO(merchant, user);

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

    public void updateScore(Long merchantId, java.math.BigDecimal avgScore, Integer reviewCount) {
        Merchant merchant = getById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }
        merchant.setAvgScore(avgScore);
        merchant.setReviewCount(reviewCount);
        updateById(merchant);
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
