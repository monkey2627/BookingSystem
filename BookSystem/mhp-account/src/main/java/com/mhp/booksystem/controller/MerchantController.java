package com.mhp.booksystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.service.MerchantService;
import com.mhp.booksystem.vo.MerchantVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @PutMapping("/info")
    public Result<?> updateInfo(@Valid @RequestBody MerchantUpdateDTO dto) {
        merchantService.updateInfo(dto);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result<MerchantVO> getDetail(@PathVariable Long id) {
        return Result.ok(merchantService.getDetail(id));
    }

    @GetMapping("/search")
    public Result<Page<MerchantVO>> search(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer serviceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(merchantService.search(city, serviceType, keyword, page, size));
    }

    @GetMapping("/my")
    public Result<MerchantVO> getMyInfo() {
        return Result.ok(merchantService.getMyInfo());
    }

    @PutMapping("/status")
    public Result<?> setShopStatus(@RequestParam int status) {
        merchantService.setShopStatus(status);
        return Result.ok();
    }
}
