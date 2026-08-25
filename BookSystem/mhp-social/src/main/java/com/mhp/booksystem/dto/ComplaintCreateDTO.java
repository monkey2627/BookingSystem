package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交投诉 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class ComplaintCreateDTO {

    @NotNull(message = "关联订单不能为空")
    private Long orderId;

    @NotBlank(message = "投诉原因不能为空")
    @Size(max = 500, message = "投诉原因不能超过500字")
    private String reason;

    // 证据图片可选，@Size 用于集合时校验元素个数，null 时跳过
    @Size(max = 10, message = "证据最多上传10张")
    private List<String> evidence;
}
