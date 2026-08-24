package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约表（数据库表名为 order，但代码全部用 Booking 命名）。
 *
 * 状态机（status）：
 *   0=待确认 ──confirm()──▶ 2=已定档 ──complete()──▶ 3=已完成
 *        │                                                  │
 *        └──────────────cancel()────────────────────────▶ 4=已取消
 *
 *   状态 1（待付款）保留在枚举中但当前未使用，无支付集成，confirm() 直接跳到 2。
 *
 * orderNo 和 id 的区别：
 *   id 是数据库自增主键，内部使用。
 *   orderNo 是对外展示的订单号（UUID），防止客人通过连续 id 猜出订单总量。
 *
 * serviceType 冗余说明：
 *   serviceType 从 Schedule 复制过来，这样展示预约列表时不需要 JOIN schedule 表查类型，
 *   牺牲少量存储换取查询性能。
 */
@Data
public class Booking {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** UUID 对外订单号，前端展示用 */
    private String orderNo;

    private Long userId;
    private Long merchantId;
    private Long scheduleId;

    /** 0=待确认 1=待付款(未用) 2=已定档 3=已完成 4=已取消 */
    private Integer status;

    /** 客人备注，最大 200 字 */
    private String remark;

    /** 问卷答案，JSON 字符串，格式由前端拼接，后端透传存储 */
    private String questionnaireAnswer;

    /** 冗余自 Schedule.serviceType，查列表时免 JOIN */
    private Integer serviceType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
