package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建问卷模板 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class QuestionnaireCreateDTO {

    @NotBlank(message = "问卷标题不能为空")
    @Size(max = 100, message = "问卷标题不能超过100字")
    private String title;

    // questions 是 JSON 数组字符串（如 "[{\"type\":1,\"label\":\"服装颜色\"}]"）
    // @NotNull 保证非空（用 @NotNull 而非 @NotBlank，因为格式由前端序列化保证，Service 层再解析）
    @NotNull(message = "题目不能为空")
    @Size(max = 5000, message = "题目内容过长")
    private String questions;

    // 0=非必填 1=必填，有默认值 0，前端可不传
    // @Min / @Max 防止传入 2、-1 等非法值，null 时跳过（由字段默认值保证不为 null）
    @Min(value = 0, message = "isRequired 只能为 0 或 1")
    @Max(value = 1, message = "isRequired 只能为 0 或 1")
    private Integer isRequired = 0;
}
