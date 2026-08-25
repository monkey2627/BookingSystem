package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 创建单个档期 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class ScheduleCreateDTO {

    // @Future：日期/时间必须是将来的时刻，null 时跳过（由 @NotNull 保证非 null）
    @NotNull(message = "日期不能为空")
    @Future(message = "档期日期必须是未来的日期")
    private LocalDate date;

    // @Pattern：正则匹配，null 时跳过（null 表示全天档，允许不填）
    // 非 null 时必须满足 HH:mm-HH:mm 格式，如 "09:00-12:00"
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d$",
             message = "时间段格式不正确，应为 HH:mm-HH:mm，如 09:00-12:00")
    @Size(max = 11, message = "时间段格式不正确")
    private String timeSlot;

    // @Min / @Max：整数范围校验，null 时跳过（由 @NotNull 保证非 null）
    // 0=普通预约 1=抢档期，超出 0-1 视为非法请求
    @NotNull(message = "预约模式不能为空")
    @Min(value = 0, message = "预约模式无效")
    @Max(value = 1, message = "预约模式无效")
    private Integer bookType;

    @NotNull(message = "服务类型不能为空")
    @Min(value = 1, message = "服务类型无效")
    @Max(value = 7, message = "服务类型无效")
    private Integer serviceType;

    // 条件必填字段：bookType=1（抢档期）时此字段必须有值
    // Bean Validation 标准注解无法表达"当字段 A 等于某值时字段 B 必填"这类跨字段逻辑
    // 需要在 Service 层手动 if 判断：if (bookType == 1 && rushOpenTime == null) throw ...
    private LocalDateTime rushOpenTime;

    // null 时跳过（不填表示不限制队列人数），非 null 时限制在 1-500 范围内
    @Min(value = 1, message = "排队人数至少1人")
    @Max(value = 500, message = "排队人数最多500人")
    private Integer maxQueueSize;
}
