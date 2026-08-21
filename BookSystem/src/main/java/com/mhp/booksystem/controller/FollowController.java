package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.service.FollowService;
import com.mhp.booksystem.vo.MerchantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** 关注商家 */
    @PostMapping("/{merchantId}")
    public Result<?> follow(@PathVariable Long merchantId) {
        followService.follow(merchantId);
        return Result.ok();
    }

    /** 取消关注 */
    @DeleteMapping("/{merchantId}")
    public Result<?> unfollow(@PathVariable Long merchantId) {
        followService.unfollow(merchantId);
        return Result.ok();
    }

    /** 查询是否已关注 */
    @GetMapping("/{merchantId}/status")
    public Result<Boolean> isFollowing(@PathVariable Long merchantId) {
        return Result.ok(followService.isFollowing(merchantId));
    }

    /** 获取我关注的商家列表 */
    @GetMapping("/my")
    public Result<List<MerchantVO>> myFollows() {
        return Result.ok(followService.myFollows());
    }
}
