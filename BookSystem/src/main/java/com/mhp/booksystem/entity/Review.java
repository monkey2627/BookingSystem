package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Review {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long userId;

    private Long merchantId;

    /** 评分 1-5 */
    private Integer score;

    private String content;

    /** 评价图片 URL 数组 JSON */
    private String images;

    private String reply;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
