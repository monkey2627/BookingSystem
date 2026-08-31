package com.mhp.booksystem.feign;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 声明式 HTTP 客户端 — 对应 mhp-account 服务的内部接口。
 *
 * 调用方：mhp-booking（取消预约时拼装商家信息）
 *         mhp-social（展示用户/商家信息）
 *
 * 路由：Feign 通过 name="mhp-account" 从 Nacos 服务注册表找到实例，
 *       负载均衡后直接调用目标服务，不经过 Gateway，避免多一跳和额外鉴权。
 *
 * 接口路径前缀 /internal/**，对应 AccountInternalController，
 * 该 Controller 的 SaTokenConfig 已将 /internal/** 加入白名单，无需 token。
 */
@FeignClient(name = "mhp-account")
public interface AccountFeignClient {

    /** 按 id 查单个用户（昵称+头像），主要用于消息/动态展示 */
    @GetMapping("/internal/user/{id}")
    Result<UserDTO> getUser(@PathVariable("id") Long id);

    /** 批量查用户，避免 N+1 问题（如动态列表需要展示多个商家头像） */
    @GetMapping("/internal/user/batch")
    Result<List<UserDTO>> batchGetUsers(@RequestParam("ids") List<Long> ids);

    /** 按商家 id 查商家信息 */
    @GetMapping("/internal/merchant/{id}")
    Result<MerchantDTO> getMerchant(@PathVariable("id") Long id);

    /**
     * 按 userId 查商家信息 — 最常用的接口。
     * Service 层从 Sa-Token 拿到 userId，再通过此接口换取 merchantId，
     * 避免在 booking/social 服务里存储 userId→merchantId 的映射关系。
     */
    @GetMapping("/internal/merchant/by-user/{userId}")
    Result<MerchantDTO> getMerchantByUserId(@PathVariable("userId") Long userId);

    /** 批量查商家，用于关注列表、动态列表等需要展示多个商家的场景 */
    @GetMapping("/internal/merchant/batch")
    Result<List<MerchantDTO>> batchGetMerchants(@RequestParam("ids") List<Long> ids);

}
