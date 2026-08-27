package com.mhp.booksystem.rpc;

import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.MerchantScoreUpdateDTO;

/**
 * Dubbo3 RPC 服务接口：商家相关强业务依赖调用。
 *
 * 下沉到 mhp-common，Provider（mhp-account）和 Consumer（mhp-booking/mhp-social）
 * 共同依赖同一接口，编译期即可发现参数类型不匹配，无需等到运行时。
 */
public interface RpcMerchantService {

    MerchantDTO getMerchantByUserId(Long userId);

    void updateMerchantScore(Long merchantId, MerchantScoreUpdateDTO dto);
}
