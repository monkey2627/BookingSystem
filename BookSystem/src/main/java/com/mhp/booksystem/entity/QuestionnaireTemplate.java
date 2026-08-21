package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QuestionnaireTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String title;

    /** 题目 JSON 数组，含题目/类型/选项/是否必填 */
    private String questions;

    /** 0=否 1=是 */
    private Integer isRequired;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
