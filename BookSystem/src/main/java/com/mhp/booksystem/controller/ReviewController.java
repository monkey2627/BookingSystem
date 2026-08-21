package com.mhp.booksystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.ReviewCreateDTO;
import com.mhp.booksystem.dto.ReviewReplyDTO;
import com.mhp.booksystem.service.ReviewService;
import com.mhp.booksystem.vo.ReviewVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    public Result<?> create(@Valid @RequestBody ReviewCreateDTO dto) {
        reviewService.create(dto);
        return Result.ok();
    }

    @GetMapping("/merchant/{merchantId}")
    public Result<Page<ReviewVO>> listByMerchant(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(reviewService.listByMerchant(merchantId, page, size));
    }

    @PutMapping("/{id}/reply")
    public Result<?> reply(@PathVariable Long id, @Valid @RequestBody ReviewReplyDTO dto) {
        reviewService.reply(id, dto);
        return Result.ok();
    }
}
