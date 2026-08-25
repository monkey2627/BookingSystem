package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class MessageSendDTO {

    // @NotNull 适合 Long/Integer 等非字符串类型；字符串用 @NotBlank
    @NotNull(message = "接收方不能为空")
    private Long toUserId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "消息内容不能超过1000字")
    private String content;

    // 0=文字 1=图片，有默认值 0
    // @Min / @Max 防止前端传入非法值（如 99），null 时跳过（由默认值保证不为 null）
    @Min(value = 0, message = "消息类型无效")
    @Max(value = 1, message = "消息类型无效")
    private Integer msgType = 0;
}
