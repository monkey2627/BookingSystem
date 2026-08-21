package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Post {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String content;

    /** 图片 URL 数组 JSON，如 ["url1","url2"] */
    private String images;

    private Integer likeCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
