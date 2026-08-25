package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求体 DTO。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class UserLoginDTO {

    // @NotBlank：null / "" / "   " 均不通过，是字符串字段最常用的非空校验
    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;
}
