package com.mhp.booksystem.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.MerchantScoreUpdateDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.service.MerchantService;
import com.mhp.booksystem.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 内部接口，仅供其他微服务通过 OpenFeign 调用，不经过 Gateway，不需要 token 校验。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class AccountInternalController {

    private final UserService userService;
    private final MerchantService merchantService;

    @GetMapping("/user/{id}")
    public Result<UserDTO> getUser(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null) return Result.ok(null);
        return Result.ok(toUserDTO(user));
    }

    @GetMapping("/user/batch")
    public Result<List<UserDTO>> batchGetUsers(@RequestParam List<Long> ids) {
        List<User> users = userService.listByIds(ids);
        return Result.ok(users.stream().map(this::toUserDTO).collect(Collectors.toList()));
    }

    @GetMapping("/merchant/{id}")
    public Result<MerchantDTO> getMerchant(@PathVariable Long id) {
        Merchant merchant = merchantService.getById(id);
        if (merchant == null) return Result.ok(null);
        return Result.ok(toMerchantDTO(merchant));
    }

    @GetMapping("/merchant/by-user/{userId}")
    public Result<MerchantDTO> getMerchantByUserId(@PathVariable Long userId) {
        Merchant merchant = merchantService.getOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId));
        if (merchant == null) return Result.ok(null);
        return Result.ok(toMerchantDTO(merchant));
    }

    @GetMapping("/merchant/batch")
    public Result<List<MerchantDTO>> batchGetMerchants(@RequestParam List<Long> ids) {
        List<Merchant> merchants = merchantService.listByIds(ids);
        return Result.ok(merchants.stream().map(this::toMerchantDTO).collect(Collectors.toList()));
    }

    @PutMapping("/merchant/{id}/score")
    public Result<Void> updateMerchantScore(@PathVariable Long id, @RequestBody MerchantScoreUpdateDTO dto) {
        merchantService.updateScore(id, dto.getAvgScore(), dto.getReviewCount());
        return Result.ok();
    }

    /**
     * ES 全量初始化接口 — 将 MySQL merchant 表所有数据批量写入 ES。
     * 只在首次部署 ES 或 ES 数据丢失后调用一次，之后由 Canal CDC 增量同步。
     *
     * 调用方式：POST http://localhost:8081/internal/merchant/es/init
     * （内部接口，不经过 Gateway，不需要 token）
     */
    @PostMapping("/merchant/es/init")
    public Result<Void> initEsData() {
        merchantService.initEsData();
        return Result.ok();
    }

    private UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickname(user.getNickname());
        dto.setAvatar(user.getAvatar());
        return dto;
    }

    private MerchantDTO toMerchantDTO(Merchant merchant) {
        MerchantDTO dto = new MerchantDTO();
        dto.setId(merchant.getId());
        dto.setUserId(merchant.getUserId());
        dto.setServiceTypes(merchant.getServiceTypes());
        dto.setCity(merchant.getCity());
        dto.setIntro(merchant.getIntro());
        dto.setAlipayLink(merchant.getAlipayLink());
        dto.setXianyuLink(merchant.getXianyuLink());
        dto.setXiaohongshuLink(merchant.getXiaohongshuLink());
        dto.setWeiboLink(merchant.getWeiboLink());
        dto.setAvgScore(merchant.getAvgScore());
        dto.setReviewCount(merchant.getReviewCount());
        dto.setPriceMin(merchant.getPriceMin());
        dto.setPriceMax(merchant.getPriceMax());
        dto.setBookingNotice(merchant.getBookingNotice());
        return dto;
    }
}
