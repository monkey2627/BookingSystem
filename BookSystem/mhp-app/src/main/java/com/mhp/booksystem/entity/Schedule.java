package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private LocalDate date;

    /** 如 09:00-12:00 */
    private String timeSlot;

    /** 0=空闲 1=已预约 2=不可用 */
    private Integer status;

    /** 0=直接预约 1=抢档期 */
    private Integer bookType;

    private LocalDateTime rushOpenTime;

    private Integer maxQueueSize;

    /** 1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他 */
    private Integer serviceType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
