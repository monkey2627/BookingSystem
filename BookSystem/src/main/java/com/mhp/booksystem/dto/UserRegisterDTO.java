package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * DTO（Data Transfer Object）— 前端传给后端的数据载体
 *
 * 传输方向：前端请求体 → Controller 方法参数（@RequestBody 接收）
 *
 * 为什么不直接用 Entity（User）来接收？
 *   1. 安全：Entity 和数据库表一一对应，包含所有字段（如 createTime、isDeleted），
 *      前端不应该能传这些字段进来，否则可能被恶意篡改。
 *   2. 字段不匹配：注册只需要 phone/password/nickname/role，
 *      而 User 表有十几个字段，用 Entity 接收语义不清晰。
 *
 * @Valid 注解配合字段上的校验注解（@NotBlank、@Pattern 等），
 * 参数不合法时由 GlobalExceptionHandler.handleValidation() 统一返回错误信息。
 */
@Data
public class UserRegisterDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "昵称不能为空")
    private String nickname;
}
