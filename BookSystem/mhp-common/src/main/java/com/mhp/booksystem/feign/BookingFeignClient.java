package com.mhp.booksystem.feign;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.BookingDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "mhp-booking")
public interface BookingFeignClient {

    @GetMapping("/internal/booking/{id}")
    Result<BookingDTO> getBooking(@PathVariable("id") Long id);
}
