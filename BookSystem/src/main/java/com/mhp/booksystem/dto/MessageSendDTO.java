package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MessageSendDTO {

    @NotNull(message = "接收方不能为空")
    private Long toUserId;

    @NotBlank(message = "消息内容不能为空")
    private String content;

    /** 0=文字 1=图片 */
    private Integer msgType = 0;
}
