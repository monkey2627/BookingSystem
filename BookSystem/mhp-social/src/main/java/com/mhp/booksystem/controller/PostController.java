package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.security.SecurityUtil;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    @PostMapping("/{id}/like")
    public Result<?> toggleLike(@PathVariable Long id) {
        postService.toggleLike(id);
        return Result.ok();
    }

    @GetMapping("/feed")
    public Result<CursorPageVO<PostVO>> feed(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size) {
        // 可选认证：有 token → 个性化 feed；无 token → 通用 feed
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = (auth instanceof UsernamePasswordAuthenticationToken)
                ? (Long) auth.getPrincipal() : null;
        return Result.ok(postService.listAll(lastId, size, currentUserId));
    }

    @GetMapping("/followed-feed")
    public Result<CursorPageVO<PostVO>> followedFeed(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size) {
        Long currentUserId = SecurityUtil.getCurrentUserId();
        return Result.ok(postService.followedFeed(lastId, size, currentUserId));
    }
}
