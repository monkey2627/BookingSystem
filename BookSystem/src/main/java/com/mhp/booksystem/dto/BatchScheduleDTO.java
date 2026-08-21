package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量创建档期 DTO
 *
 * 使用场景：商家选定一段日期范围，并指定哪几天（周一到周日），
 * 系统自动为范围内符合条件的每一天各创建一个档期，跳过已存在的日期不报错。
 */
@Data
public class BatchScheduleDTO {

    /** 日期范围起始（含） */
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    /** 日期范围结束（含） */
    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;

    /**
     * 限定创建哪几天，1=周一, 2=周二, ..., 7=周日。
     * 为 null 或空列表时表示范围内每天都创建。
     */
    private List<Integer> weekdays;

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

    /** bookType=1 时建议填写，抢档期开放时间 */
    private LocalDateTime rushOpenTime;

    /** 抢档期最大排队人数，默认 10 */
    private Integer maxQueueSize;
}
