package com.mhp.booksystem.rpc;

import com.mhp.booksystem.dto.feign.BookingDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService(version = "1.0.0")
@RequiredArgsConstructor
public class RpcBookingServiceImpl implements RpcBookingService {

    private final BookingService bookingService;

    @Override
    public BookingDTO getBookingById(Long bookingId) {
        Booking booking = bookingService.getById(bookingId);
        if (booking == null) return null;
        return toBookingDTO(booking);
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
