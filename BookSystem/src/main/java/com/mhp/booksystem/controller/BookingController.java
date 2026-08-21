package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.BookingCreateDTO;
import com.mhp.booksystem.service.BookingService;
import com.mhp.booksystem.vo.BookingVO;
import com.mhp.booksystem.vo.CursorPageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public Result<?> create(@Valid @RequestBody BookingCreateDTO dto) {
        bookingService.create(dto);
        return Result.ok();
    }

    @GetMapping("/my")
    public Result<CursorPageVO<BookingVO>> myBookings(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer serviceType) {
        return Result.ok(bookingService.myBookings(lastId, size, serviceType));
    }

    @GetMapping("/received")
    public Result<CursorPageVO<BookingVO>> receivedBookings(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer serviceType,
            @RequestParam(required = false) Integer status) {
        return Result.ok(bookingService.receivedBookings(lastId, size, serviceType, status));
    }

    @PutMapping("/{id}/confirm")
    public Result<?> confirm(@PathVariable Long id) {
        bookingService.confirm(id);
        return Result.ok();
    }

    @PutMapping("/{id}/complete")
    public Result<?> complete(@PathVariable Long id) {
        bookingService.complete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/cancel")
    public Result<?> cancel(@PathVariable Long id) {
        bookingService.cancel(id);
        return Result.ok();
    }
}
