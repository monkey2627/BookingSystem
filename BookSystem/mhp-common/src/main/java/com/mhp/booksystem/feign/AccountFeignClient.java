package com.mhp.booksystem.feign;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.MerchantScoreUpdateDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "mhp-account")
public interface AccountFeignClient {

    @GetMapping("/internal/user/{id}")
    Result<UserDTO> getUser(@PathVariable("id") Long id);

    @GetMapping("/internal/user/batch")
    Result<List<UserDTO>> batchGetUsers(@RequestParam("ids") List<Long> ids);

    @GetMapping("/internal/merchant/{id}")
    Result<MerchantDTO> getMerchant(@PathVariable("id") Long id);

    @GetMapping("/internal/merchant/by-user/{userId}")
    Result<MerchantDTO> getMerchantByUserId(@PathVariable("userId") Long userId);

    @GetMapping("/internal/merchant/batch")
    Result<List<MerchantDTO>> batchGetMerchants(@RequestParam("ids") List<Long> ids);

    @PutMapping("/internal/merchant/{id}/score")
    Result<Void> updateMerchantScore(@PathVariable("id") Long id, @RequestBody MerchantScoreUpdateDTO dto);
}
