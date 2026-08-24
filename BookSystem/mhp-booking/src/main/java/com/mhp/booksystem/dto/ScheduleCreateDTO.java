package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ScheduleCreateDTO {

    @NotNull(message = "日期不能为空")
    @Future(message = "档期日期必须是未来的日期")
    private LocalDate date;

    private String timeSlot;

    @NotNull(message = "预约模式不能为空")
    private Integer bookType;

    @NotNull(message = "服务类型不能为空")
    @Min(value = 1, message = "服务类型无效")
    @Max(value = 7, message = "服务类型无效")
    private Integer serviceType;

    private LocalDateTime rushOpenTime;

    private Integer maxQueueSize;
}
