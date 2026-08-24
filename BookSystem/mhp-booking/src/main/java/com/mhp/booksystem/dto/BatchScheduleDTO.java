package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BatchScheduleDTO {

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;

    private List<Integer> weekdays;

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
