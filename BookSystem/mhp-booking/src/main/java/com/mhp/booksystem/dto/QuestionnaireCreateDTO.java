package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QuestionnaireCreateDTO {

    @NotBlank(message = "问卷标题不能为空")
    private String title;

    @NotNull(message = "题目不能为空")
    private String questions;

    private Integer isRequired = 0;
}
