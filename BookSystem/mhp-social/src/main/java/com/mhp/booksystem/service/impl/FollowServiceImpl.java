package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.service.FollowService;
import com.mhp.booksystem.vo.MerchantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    private final AccountFeignClient accountFeignClient;

    @Override
    public void follow(Long merchantId) {
        if (accountFeignClient.getMerchant(merchantId).getData() == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }

        Long userId = StpUtil.getLoginIdAsLong();
        boolean exists = lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getMerchantId, merchantId)
                .exists();
        if (exists) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "您已关注该商家");
        }

        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setMerchantId(merchantId);
        save(follow);
    }

    @Override
    public void unfollow(Long merchantId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Follow follow = lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getMerchantId, merchantId)
                .one();
        if (follow == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "您尚未关注该商家");
        }
        removeById(follow.getId());
    }

    @Override
    public boolean isFollowing(Long merchantId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getMerchantId, merchantId)
                .exists();
    }

    @Override
    public List<MerchantVO> myFollows() {
        Long userId = StpUtil.getLoginIdAsLong();

        List<Long> merchantIds = lambdaQuery()
                .eq(Follow::getUserId, userId)
                .orderByDesc(Follow::getCreateTime)
                .list()
                .stream().map(Follow::getMerchantId).collect(Collectors.toList());

        if (merchantIds.isEmpty()) return Collections.emptyList();

        List<MerchantDTO> merchants = accountFeignClient.batchGetMerchants(merchantIds).getData();
        if (merchants == null || merchants.isEmpty()) return Collections.emptyList();

        List<Long> userIds = merchants.stream().map(MerchantDTO::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> users = accountFeignClient.batchGetUsers(userIds).getData();
        Map<Long, UserDTO> userMap = users == null ? Collections.emptyMap()
                : users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        return merchants.stream().map(m -> toMerchantVO(m, userMap.get(m.getUserId()))).collect(Collectors.toList());
    }

    private MerchantVO toMerchantVO(MerchantDTO dto, UserDTO user) {
        MerchantVO vo = new MerchantVO();
        vo.setId(dto.getId());
        vo.setUserId(dto.getUserId());
        vo.setCity(dto.getCity());
        vo.setIntro(dto.getIntro());
        vo.setAlipayLink(dto.getAlipayLink());
        vo.setXianyuLink(dto.getXianyuLink());
        vo.setXiaohongshuLink(dto.getXiaohongshuLink());
        vo.setWeiboLink(dto.getWeiboLink());
        vo.setAvgScore(dto.getAvgScore());
        vo.setReviewCount(dto.getReviewCount());
        vo.setPriceMin(dto.getPriceMin());
        vo.setPriceMax(dto.getPriceMax());
        vo.setBookingNotice(dto.getBookingNotice());
        if (dto.getServiceTypes() != null) {
            vo.setServiceTypes(JSONUtil.toList(JSONUtil.parseArray(dto.getServiceTypes()), Integer.class));
        }
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        return vo;
    }
}
