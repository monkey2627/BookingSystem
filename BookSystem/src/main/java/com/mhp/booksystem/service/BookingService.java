package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.BookingCreateDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.vo.BookingVO;
import com.mhp.booksystem.vo.CursorPageVO;

public interface BookingService extends IService<Booking> {

    void create(BookingCreateDTO dto);

    CursorPageVO<BookingVO> myBookings(Long lastId, int size, Integer serviceType);

    CursorPageVO<BookingVO> receivedBookings(Long lastId, int size, Integer serviceType, Integer status);

    void confirm(Long bookingId);

    void complete(Long bookingId);

    void cancel(Long bookingId);
}
