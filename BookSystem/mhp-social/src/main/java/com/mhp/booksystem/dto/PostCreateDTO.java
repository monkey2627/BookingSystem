package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发布动态 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class PostCreateDTO {

    // @NotBlank 保证内容非空，@Size 再限制上限（两个注解可叠加，都通过才算合法）
    @NotBlank(message = "动态内容不能为空")
    @Size(max = 2000, message = "动态内容不能超过2000字")
    private String content;

    // 图片可选（纯文字动态），@Size 用于集合时校验元素个数，参考主流平台上限 9 张
    @Size(max = 9, message = "最多上传9张图片")
    private List<String> images;
}
