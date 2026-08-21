package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * DTO（Data Transfer Object）— 前端传给后端的数据载体
 *
 * 传输方向：前端请求体 → Controller 方法参数（@RequestBody 接收）
 * 登录只需要手机号和密码，比 UserRegisterDTO 更精简。
 * 各接口单独定义 DTO，职责清晰，互不影响。
 */
@Data
public class UserLoginDTO {

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;
}
