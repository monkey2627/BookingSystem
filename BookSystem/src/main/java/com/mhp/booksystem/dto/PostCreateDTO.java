package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class PostCreateDTO {

    @NotBlank(message = "动态内容不能为空")
    private String content;

    private List<String> images;
}
