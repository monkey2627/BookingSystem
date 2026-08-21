package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Complaint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long complainantId;

    private Long respondentId;

    private String reason;

    /** 证据图片 URL 数组 JSON */
    private String evidence;

    /** 0=待处理 1=处理中 2=已处理 */
    private Integer status;

    private String adminReply;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
