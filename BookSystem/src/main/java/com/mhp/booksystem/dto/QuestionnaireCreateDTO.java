package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QuestionnaireCreateDTO {

    @NotBlank(message = "问卷标题不能为空")
    private String title;

    /**
     * 题目 JSON 数组字符串，结构示例：
     * [{"title":"拍摄风格","type":"radio","options":["古风","现代"],"required":true}]
     * type 可选值：radio / checkbox / text
     */
    @NotNull(message = "题目不能为空")
    private String questions;

    /** 是否强制填写：0=否 1=是 */
    private Integer isRequired = 0;
}
