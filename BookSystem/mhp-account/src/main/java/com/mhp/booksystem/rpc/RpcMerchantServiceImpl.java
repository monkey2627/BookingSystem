package com.mhp.booksystem.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.MerchantScoreUpdateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.service.MerchantService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService(version = "1.0.0")
@RequiredArgsConstructor
public class RpcMerchantServiceImpl implements RpcMerchantService {

    private final MerchantService merchantService;

    @Override
    public MerchantDTO getMerchantByUserId(Long userId) {
        Merchant merchant = merchantService.getOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId));
        if (merchant == null) return null;
        return toMerchantDTO(merchant);
    }

    @Override
    public void updateMerchantScore(Long merchantId, MerchantScoreUpdateDTO dto) {
        merchantService.updateScore(merchantId, dto.getAvgScore(), dto.getReviewCount());
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
