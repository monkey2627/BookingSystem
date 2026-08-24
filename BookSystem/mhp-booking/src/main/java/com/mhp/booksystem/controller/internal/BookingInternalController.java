package com.mhp.booksystem.controller.internal;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.BookingDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 内部接口，仅供其他微服务通过 OpenFeign 调用，不经过 Gateway，不需要 token 校验。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class BookingInternalController {

    private final BookingService bookingService;

    @GetMapping("/booking/{id}")
    public Result<BookingDTO> getBooking(@PathVariable Long id) {
        Booking booking = bookingService.getById(id);
        if (booking == null) return Result.ok(null);
        return Result.ok(toBookingDTO(booking));
    }

    private BookingDTO toBookingDTO(Booking booking) {
        BookingDTO dto = new BookingDTO();
        dto.setId(booking.getId());
        dto.setUserId(booking.getUserId());
        dto.setMerchantId(booking.getMerchantId());
        dto.setScheduleId(booking.getScheduleId());
        dto.setStatus(booking.getStatus());
        dto.setServiceType(booking.getServiceType());
        return dto;
    }
}
