package com.mhp.booksystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.service.MerchantService;
import com.mhp.booksystem.vo.MerchantStatsVO;
import com.mhp.booksystem.vo.MerchantVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    /** 商家完善/更新自己的资料 */
    @PutMapping("/info")
    public Result<?> updateInfo(@Valid @RequestBody MerchantUpdateDTO dto) {
        merchantService.updateInfo(dto);
        return Result.ok();
    }

    /** 查看商家主页 */
    @GetMapping("/{id}")
    public Result<MerchantVO> getDetail(@PathVariable Long id) {
        return Result.ok(merchantService.getDetail(id));
    }

    /**
     * 搜索商家
     * 示例：GET /api/merchant/search?city=北京&serviceType=1&keyword=古风&page=1&size=10
     */
    @GetMapping("/search")
    public Result<Page<MerchantVO>> search(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer serviceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(merchantService.search(city, serviceType, keyword, page, size));
    }

    /** 获取当前登录商家自己的信息（含 merchantId） */
    @GetMapping("/my")
    public Result<MerchantVO> getMyInfo() {
        return Result.ok(merchantService.getMyInfo());
    }

    /** 商家数据看板统计（本月订单 + 评分），仅商家本人可查 */
    @GetMapping("/stats")
    public Result<MerchantStatsVO> getStats() {
        return Result.ok(merchantService.getStats());
    }
}
