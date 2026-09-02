package com.mhp.booksystem.service.impl;

import com.mhp.booksystem.security.SecurityUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.ReviewCreateDTO;
import com.mhp.booksystem.dto.ReviewReplyDTO;
import com.mhp.booksystem.dto.feign.BookingDTO;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.MerchantScoreUpdateDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Review;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.rpc.RpcBookingService;
import com.mhp.booksystem.rpc.RpcMerchantService;
import com.mhp.booksystem.mapper.ReviewMapper;
import com.mhp.booksystem.service.ReviewService;
import com.mhp.booksystem.vo.ReviewVO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl extends ServiceImpl<ReviewMapper, Review> implements ReviewService {

    private final AccountFeignClient accountFeignClient;

    @DubboReference(version = "1.0.0")
    private RpcMerchantService rpcMerchantService;

    @DubboReference(version = "1.0.0")
    private RpcBookingService rpcBookingService;

    @Override
    @Transactional
    public void create(ReviewCreateDTO dto) {
        Long userId = SecurityUtil.getCurrentUserId();

        BookingDTO booking = rpcBookingService.getBookingById(dto.getOrderId());
        if (booking == null || !booking.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.BOOKING_NOT_FOUND);
        }
        if (booking.getStatus() != 3) {
            throw new BusinessException(ResultCode.REVIEW_BOOKING_NOT_COMPLETE);
        }

        boolean exists = lambdaQuery().eq(Review::getOrderId, dto.getOrderId()).exists();
        if (exists) {
            throw new BusinessException(ResultCode.REVIEW_ALREADY_EXISTS);
        }

        Review review = new Review();
        review.setOrderId(dto.getOrderId());
        review.setUserId(userId);
        review.setMerchantId(booking.getMerchantId());
        review.setScore(dto.getScore());
        review.setContent(dto.getContent());
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            review.setImages(JSONUtil.toJsonStr(dto.getImages()));
        }
        save(review);

        updateMerchantScore(booking.getMerchantId());
    }

    @Override
    public Page<ReviewVO> listByMerchant(Long merchantId, int page, int size) {
        Page<Review> reviewPage = lambdaQuery()
                .eq(Review::getMerchantId, merchantId)
                .orderByDesc(Review::getCreateTime)
                .page(new Page<>(page, size));

        List<Review> records = reviewPage.getRecords();
        if (records.isEmpty()) {
            Page<ReviewVO> empty = new Page<>(page, size, 0);
            empty.setRecords(Collections.emptyList());
            return empty;
        }

        List<Long> userIds = records.stream().map(Review::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> users = accountFeignClient.batchGetUsers(userIds).getData();
        Map<Long, UserDTO> userMap = users == null ? Collections.emptyMap()
                : users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        List<ReviewVO> voList = records.stream()
                .map(r -> toVO(r, userMap.get(r.getUserId())))
                .collect(Collectors.toList());

        Page<ReviewVO> result = new Page<>(reviewPage.getCurrent(), reviewPage.getSize(), reviewPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    public void reply(Long reviewId, ReviewReplyDTO dto) {
        Long userId = SecurityUtil.getCurrentUserId();
        Review review = getById(reviewId);
        if (review == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }

        MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(userId);
        if (merchant == null || !review.getMerchantId().equals(merchant.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        Review update = new Review();
        update.setId(reviewId);
        update.setReply(dto.getReply());
        updateById(update);
    }

    private void updateMerchantScore(Long merchantId) {
        List<Review> reviews = lambdaQuery().eq(Review::getMerchantId, merchantId).list();
        int count = reviews.size();
        double avg = reviews.stream().mapToInt(Review::getScore).average().orElse(0);
        BigDecimal avgScore = BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);

        MerchantScoreUpdateDTO scoreDTO = new MerchantScoreUpdateDTO();
        scoreDTO.setAvgScore(avgScore);
        scoreDTO.setReviewCount(count);
        rpcMerchantService.updateMerchantScore(merchantId, scoreDTO);
    }

    private ReviewVO toVO(Review r, UserDTO user) {
        ReviewVO vo = new ReviewVO();
        vo.setId(r.getId());
        vo.setOrderId(r.getOrderId());
        vo.setUserId(r.getUserId());
        vo.setScore(r.getScore());
        vo.setContent(r.getContent());
        vo.setReply(r.getReply());
        vo.setCreateTime(r.getCreateTime());
        if (StringUtils.hasText(r.getImages())) {
            vo.setImages(JSONUtil.toList(JSONUtil.parseArray(r.getImages()), String.class));
        } else {
            vo.setImages(Collections.emptyList());
        }
        if (user != null) {
            vo.setUserNickname(user.getNickname());
            vo.setUserAvatar(user.getAvatar());
        }
        return vo;
    }
}
