package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量创建档期 DTO（按星期几批量生成一段日期范围内的档期）。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class BatchScheduleDTO {

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;

    // startDate <= endDate 的关系校验需要在 Service 层做
    // Bean Validation 无法直接比较同一对象内两个字段之间的关系

    // @Size 用于集合时校验元素个数：min=1 保证至少选一个星期几
    // 元素值必须在 1~7（1=周一 … 7=周日），元素值范围在 Service 层校验
    @Size(min = 1, message = "至少选择一个星期几")
    private List<Integer> weekdays;

    // null 表示全天档；非 null 时校验 HH:mm-HH:mm 格式
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d$",
             message = "时间段格式不正确，应为 HH:mm-HH:mm，如 09:00-12:00")
    @Size(max = 11, message = "时间段格式不正确")
    private String timeSlot;

    // 0=普通预约 1=抢档期
    @NotNull(message = "预约模式不能为空")
    @Min(value = 0, message = "预约模式无效")
    @Max(value = 1, message = "预约模式无效")
    private Integer bookType;

    @NotNull(message = "服务类型不能为空")
    @Min(value = 1, message = "服务类型无效")
    @Max(value = 7, message = "服务类型无效")
    private Integer serviceType;

    // bookType=1 时必填，跨字段条件校验在 Service 层处理
    private LocalDateTime rushOpenTime;

    // null 时跳过，非 null 时限制范围
    @Min(value = 1, message = "排队人数至少1人")
    @Max(value = 500, message = "排队人数最多500人")
    private Integer maxQueueSize;
}
