package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建预约 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class BookingCreateDTO {

    // @NotNull：只拦截 null，适合非字符串类型（Long、Integer 等基本类型包装类）
    // 字符串类型用 @NotBlank 更合适，因为它同时拦截空字符串和纯空格
    @NotNull(message = "档期不能为空")
    private Long scheduleId;

    // @Size 用于字符串时校验字符数（Unicode 码点），null 时跳过（备注为可选字段）
    @Size(max = 500, message = "备注不能超过500字")
    private String remark;

    // 问卷答案为 JSON 字符串，null 表示未填问卷，@Size 防止超大数据写入数据库
    @Size(max = 2000, message = "问卷答案内容过长")
    private String questionnaireAnswer;
}
