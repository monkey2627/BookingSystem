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

    /** 时间段，如 09:00-12:00，不填表示全天 */
    private String timeSlot;

    /** 0=直接预约 1=抢档期 */
    @NotNull(message = "预约模式不能为空")
    private Integer bookType;

    /** 1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他 */
    @NotNull(message = "服务类型不能为空")
    @Min(value = 1, message = "服务类型无效")
    @Max(value = 7, message = "服务类型无效")
    private Integer serviceType;

    /** bookType=1 时必填，抢档期开放时间 */
    private LocalDateTime rushOpenTime;

    /** 抢档期最大排队人数，默认 10 */
    private Integer maxQueueSize;
}
