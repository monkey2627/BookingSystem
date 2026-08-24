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

    @PostMapping("/{merchantId}")
    public Result<?> follow(@PathVariable Long merchantId) {
        followService.follow(merchantId);
        return Result.ok();
    }

    @DeleteMapping("/{merchantId}")
    public Result<?> unfollow(@PathVariable Long merchantId) {
        followService.unfollow(merchantId);
        return Result.ok();
    }

    @GetMapping("/{merchantId}/status")
    public Result<Boolean> isFollowing(@PathVariable Long merchantId) {
        return Result.ok(followService.isFollowing(merchantId));
    }

    @GetMapping("/my")
    public Result<List<MerchantVO>> myFollows() {
        return Result.ok(followService.myFollows());
    }
}
