package com.mhp.booksystem.rpc;

import com.mhp.booksystem.dto.feign.BookingDTO;

/**
 * Dubbo3 RPC 服务接口：预约相关强业务依赖调用。
 *
 * mhp-social 在发起评价/投诉前必须校验预约状态，
 * 失败则整个业务终止，无降级空间，适合 RPC 强调用。
 */
public interface RpcBookingService {

    BookingDTO getBookingById(Long bookingId);
}
