package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Booking {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long merchantId;

    private Long scheduleId;

    /** 0=待确认 1=待付款 2=已定档 3=已完成 4=已取消 */
    private Integer status;

    private String remark;

    /** 问卷答案 JSON */
    private String questionnaireAnswer;

    /** 1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他（冗余自 Schedule，避免 JOIN） */
    private Integer serviceType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
