package com.mhp.booksystem.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.PostCreateDTO;
import com.mhp.booksystem.service.PostService;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.PostVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @PostMapping
    public Result<?> create(@Valid @RequestBody PostCreateDTO dto) {
        postService.create(dto);
        return Result.ok();
    }

    @GetMapping("/merchant/{merchantId}")
    public Result<CursorPageVO<PostVO>> listByMerchant(
            @PathVariable Long merchantId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.listByMerchant(merchantId, lastId, size));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        postService.delete(id);
        return Result.ok();
    }

    /** 点赞 / 取消点赞（需登录） */
    @PostMapping("/{id}/like")
    public Result<?> toggleLike(@PathVariable Long id) {
        postService.toggleLike(id);
        return Result.ok();
    }

    /**
     * 全站动态流（游标分页）
     * 未登录时不传 token，liked 字段始终为 false
     */
    @GetMapping("/feed")
    public Result<CursorPageVO<PostVO>> feed(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size) {
        Long currentUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return Result.ok(postService.listAll(lastId, size, currentUserId));
    }

    /** 已关注商家的动态流（需登录） */
    @GetMapping("/followed-feed")
    public Result<CursorPageVO<PostVO>> followedFeed(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        return Result.ok(postService.followedFeed(lastId, size, currentUserId));
    }
}
