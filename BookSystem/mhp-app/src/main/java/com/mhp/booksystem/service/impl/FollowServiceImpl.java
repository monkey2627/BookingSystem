package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.service.FollowService;
import com.mhp.booksystem.vo.MerchantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    private final MerchantMapper merchantMapper;

    @Override
    public void follow(Long merchantId) {
        // 检查商家是否存在
        if (merchantMapper.selectById(merchantId) == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }

        Long userId = StpUtil.getLoginIdAsLong();

        // 检查是否已关注
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
        return baseMapper.selectFollowedMerchants(userId);
    }
}
