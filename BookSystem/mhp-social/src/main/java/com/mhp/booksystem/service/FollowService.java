package com.mhp.booksystem.service;

import com.mhp.booksystem.vo.MerchantVO;

import java.util.List;

public interface FollowService {

    void follow(Long merchantId);

    void unfollow(Long merchantId);

    boolean isFollowing(Long merchantId);

    List<MerchantVO> myFollows();
}
