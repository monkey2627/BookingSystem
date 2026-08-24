package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReviewCreateDTO {

    @NotNull(message = "订单不能为空")
    private Long orderId;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低1分")
    @Max(value = 5, message = "评分最高5分")
    private Integer score;

    private String content;

    private List<String> images;
}
