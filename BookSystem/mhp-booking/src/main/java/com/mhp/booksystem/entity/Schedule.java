package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 档期表 — 商家发布的可预约时间段。
 *
 * 状态机（status）：
 *   0=空闲  →  客人预约后 → 1=已预约
 *   1=已预约 →  预约取消后 → 0=空闲
 *   2=不可用（商家手动标记，暂未在前端开放）
 *
 * 两种预约模式（bookType）：
 *   0=直接预约：客人在详情页点"预约"，BookingService 锁定档期，状态置 1。
 *   1=抢档期：  开放时间到后，客人排队（Redis ZSET），商家从队列中选人确认，
 *               选中的人再走 Booking 流程，其余人保持排队状态。
 */
@Data
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    /** 档期日期，精确到天，同一商家同一天+同一 timeSlot 唯一 */
    private LocalDate date;

    /** 时间段描述，如 "09:00-12:00"，nullable（全天档） */
    private String timeSlot;

    /** 0=空闲 1=已预约 2=不可用 */
    private Integer status;

    /** 0=直接预约 1=抢档期 */
    private Integer bookType;

    /** 抢档期的开放时间，未到该时间前 rush() 会抛 SCHEDULE_NOT_OPEN */
    private LocalDateTime rushOpenTime;

    /** 抢档期队列最大人数，默认 10，超过则返回 SCHEDULE_FULL */
    private Integer maxQueueSize;

    /** 1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他，冗余到 Booking 避免 JOIN */
    private Integer serviceType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
