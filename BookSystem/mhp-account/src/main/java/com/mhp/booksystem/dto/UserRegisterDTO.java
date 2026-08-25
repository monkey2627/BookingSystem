package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 注册请求体 DTO，同时作为 Bean Validation 注解的使用说明参考。
 *
 * ── Bean Validation 是什么 ───────────────────────────────────────────────────
 * Bean Validation 是 Java 官方规范（jakarta.validation），只定义注解和接口。
 * 具体校验逻辑由 Hibernate Validator 实现（spring-boot-starter-validation 已打包引入）。
 *
 * ── 触发流程 ─────────────────────────────────────────────────────────────────
 * 1. Controller 方法参数加 @Valid：
 *      public Result<?> register(@Valid @RequestBody UserRegisterDTO dto)
 * 2. Spring 把请求体反序列化为 DTO 对象后，立刻扫描所有字段上的校验注解。
 * 3. 任意字段不通过 → 抛出 MethodArgumentNotValidException，方法体一行不执行。
 * 4. GlobalExceptionHandler.handleValidation() 接住，取第一个错误的 message 返回给前端。
 *
 * ── 核心注解对比（最容易混淆的三个）────────────────────────────────────────
 *
 *   注解          null    ""    "   "（纯空格）    "abc"
 *   @NotNull      ❌      ✅     ✅               ✅   → 只拦 null
 *   @NotEmpty     ❌      ❌     ✅               ✅   → 拦 null 和 ""
 *   @NotBlank     ❌      ❌     ❌               ✅   → 拦 null、""、纯空格（最严格）
 *
 *   字符串字段通常选 @NotBlank，它覆盖了另外两个的功能。
 *
 * ── null 透传规则 ────────────────────────────────────────────────────────────
 * @Pattern / @Size / @Min / @Max / @Future / @DecimalMin 这类"格式/范围"注解
 * 遇到 null 值时【直接跳过，视为通过】。
 * 原因：这些注解的语义是"如果有值，值必须满足条件"，而非"必须有值"。
 * 所以"必填 + 格式"需要叠加两个注解，如本类 phone 字段：@NotBlank + @Pattern。
 *
 * ── 其他常用注解速查 ─────────────────────────────────────────────────────────
 *   @Size(min, max)     字符串 → 字符数；集合/数组 → 元素个数
 *   @Min / @Max         整数范围（Integer、Long 等），null 跳过
 *   @DecimalMin/Max     BigDecimal 范围，null 跳过
 *   @Future             日期必须是未来，null 跳过
 *   @Pattern(regexp)    正则匹配，null 跳过
 *   @Valid              标在字段上时递归校验嵌套对象（本类暂无嵌套对象）
 */
@Data
public class UserRegisterDTO {

    // @NotBlank 拦截 null/空字符串/纯空格；@Pattern 格式校验（null 自动跳过，由 @NotBlank 保证非 null）
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "昵称不能为空")
    private String nickname;
}
