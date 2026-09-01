package com.mhp.booksystem.dto.feign;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ScheduleDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long merchantId;
    private LocalDate date;
    private String timeSlot;
    private Integer status;
    private Integer bookType;
    private Integer serviceType;
    private LocalDateTime rushOpenTime;
    private Integer maxQueueSize;
    private Integer currentQueueSize;
}
