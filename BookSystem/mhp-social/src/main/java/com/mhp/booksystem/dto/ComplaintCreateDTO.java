package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ComplaintCreateDTO {

    @NotNull(message = "关联订单不能为空")
    private Long orderId;

    @NotBlank(message = "投诉原因不能为空")
    private String reason;

    private List<String> evidence;
}
