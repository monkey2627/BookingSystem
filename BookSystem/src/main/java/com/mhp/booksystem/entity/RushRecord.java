package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RushRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long scheduleId;

    private Long userId;

    private Integer rankNo;

    /** 0=排队中 1=已联系 2=已转化 3=已放弃 */
    private Integer status;

    private LocalDateTime rushTime;
}
