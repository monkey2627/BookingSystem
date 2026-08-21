package com.mhp.booksystem.service;

import com.mhp.booksystem.vo.MerchantVO;

import java.util.List;

public interface FollowService {

    /** 关注商家 */
    void follow(Long merchantId);

    /** 取消关注 */
    void unfollow(Long merchantId);

    /** 查询当前用户是否已关注指定商家 */
    boolean isFollowing(Long merchantId);

    /** 获取当前用户关注的所有商家列表 */
    List<MerchantVO> myFollows();
}
