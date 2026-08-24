package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.vo.MerchantVO;

import java.math.BigDecimal;

public interface MerchantService extends IService<Merchant> {

    void updateInfo(MerchantUpdateDTO dto);

    MerchantVO getDetail(Long merchantId);

    Page<MerchantVO> search(String city, Integer serviceType, String keyword, int page, int size);

    MerchantVO getMyInfo();

    void updateScore(Long merchantId, BigDecimal avgScore, Integer reviewCount);
}
