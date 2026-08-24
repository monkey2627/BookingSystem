package com.mhp.booksystem.dto.feign;

import lombok.Data;

@Data
public class BookingDTO {
    private Long id;
    private Long userId;
    private Long merchantId;
    private Long scheduleId;
    private Integer status;
    private Integer serviceType;
}
