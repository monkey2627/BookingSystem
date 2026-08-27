package com.mhp.booksystem.dto.feign;

import lombok.Data;

import java.io.Serializable;

@Data
public class BookingDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long userId;
    private Long merchantId;
    private Long scheduleId;
    private Integer status;
    private Integer serviceType;
}
