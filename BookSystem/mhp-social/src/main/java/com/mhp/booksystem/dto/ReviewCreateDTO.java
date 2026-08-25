package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建评价 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class ReviewCreateDTO {

    @NotNull(message = "订单不能为空")
    private Long orderId;

    // @Min / @Max 同时作用：保证评分在 1-5 分之间（null 时跳过，由 @NotNull 保证非 null）
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低1分")
    @Max(value = 5, message = "评分最高5分")
    private Integer score;

    // 评价内容可选，null 和空字符串均允许（用户只打分不写字）；非 null 时限制长度
    @Size(max = 500, message = "评价内容不能超过500字")
    private String content;

    // @Size 用于集合时校验元素个数，null 时跳过（不上传图片时不传此字段）
    @Size(max = 5, message = "最多上传5张图片")
    private List<String> images;
}
