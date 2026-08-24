package com.mhp.booksystem.feign;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.BookingDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 声明式 HTTP 客户端 — 对应 mhp-booking 服务的内部接口。
 *
 * 调用方：mhp-social（评价和投诉时需要校验预约是否存在、归属是否正确）
 *
 * 为什么不在 mhp-social 里直接查 booking 表？
 *   微服务原则：每个服务只管理自己的数据表，不允许跨服务直接查库。
 *   mhp-social 要查预约信息，只能通过 Feign 调 mhp-booking 提供的接口。
 */
@FeignClient(name = "mhp-booking")
public interface BookingFeignClient {

    /**
     * 按 id 查预约信息（userId、merchantId、status、serviceType）。
     * ReviewServiceImpl 和 ComplaintServiceImpl 用此接口校验：
     *   1. 预约是否属于当前登录用户
     *   2. 预约状态是否为"已完成"（status=3）才能评价
     */
    @GetMapping("/internal/booking/{id}")
    Result<BookingDTO> getBooking(@PathVariable("id") Long id);
}
