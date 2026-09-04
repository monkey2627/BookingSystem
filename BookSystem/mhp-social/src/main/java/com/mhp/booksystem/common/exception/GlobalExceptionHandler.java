package com.mhp.booksystem.common.exception;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * ── @RestControllerAdvice 是什么 ────────────────────────────────────────────
 * 它是一个组合注解，等价于同时写了：
 *   @ControllerAdvice  → 让这个类成为"全局增强器"，对所有 Controller 抛出的异常生效
 *   @ResponseBody      → 方法返回值自动序列化为 JSON 写入响应体
 *
 * 注意："Advice" 和 Spring AOP 里的 @Aspect / @Before / @Around 没有任何关系。
 * AOP Advice 是"在方法执行的某个时机插入逻辑"（基于动态代理）；
 * 这里的 Advice 只是英文"辅助/增强"的意思，底层是 Spring MVC 的 HandlerExceptionResolver 机制。
 *
 * ── Spring 怎么知道来这里找异常处理方法 ─────────────────────────────────────
 * 【启动阶段】Spring Boot 启动时，ComponentScan 扫描到本类上的 @RestControllerAdvice
 *   → 注册为 Bean 放入 IoC 容器
 *   → Spring MVC 内置组件 ExceptionHandlerExceptionResolver 扫描所有 @ControllerAdvice Bean，
 *     读取每个 @ExceptionHandler 方法，在内存里建好一张"异常类型 → 处理方法"映射表：
 *       BusinessException                    → handleBusiness()
 *       MethodArgumentNotValidException      → handleValidation()
 *       HttpRequestMethodNotSupportedException → handleMethodNotSupported()
 *       Exception                            → handleException()
 *
 * 【请求阶段】所有 HTTP 请求都经过 DispatcherServlet（Spring MVC 核心调度器）：
 *   DispatcherServlet.doDispatch() 内部有 try-catch，包裹了整个 Controller 调用链。
 *   当 Service/Controller 抛出异常时，异常沿调用栈自然冒泡，最终被 doDispatch 的 catch 接住，
 *   然后交给 ExceptionHandlerExceptionResolver，查映射表找到对应方法执行，返回 JSON 响应。
 *
 * 完整流程：
 *   Service 抛出异常
 *     → 冒泡到 Controller（无 try-catch）
 *       → 冒泡到 DispatcherServlet.doDispatch() 的 catch
 *         → ExceptionHandlerExceptionResolver 查映射表
 *           → 调用本类对应的 @ExceptionHandler 方法
 *             → 返回值被 @ResponseBody 序列化为 JSON 响应
 *
 * ── 为什么每个微服务各自一份 ────────────────────────────────────────────────
 * 三个微服务（mhp-account / mhp-booking / mhp-social）是独立的 Spring Boot 应用，
 * 各有独立的 Spring 容器和 ComponentScan，无法共享 Bean。
 * mhp-common 是纯 Java 库（无 @SpringBootApplication），不触发 ComponentScan，
 * 放在那里的 @RestControllerAdvice 类不会被任何容器扫描到，等于不存在，
 * 所以只能各服务单独维护一份（内容相同）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（预期内错误）。
     * 触发时机：Service 层主动 throw new BusinessException(ResultCode.XXX)。
     * 不打 error 日志，因为这是正常业务流程（档期不存在、重复预约……），不是 bug。
     * @ExceptionHandler 精确匹配 BusinessException，优先于下方的 Exception 兜底。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 请求参数校验失败。
     * 触发时机：Controller 方法参数上有 @Valid，且 DTO 字段不满足 @NotNull / @Size 等约束时，
     *   Spring 自动抛出此异常，不会进入 Controller 方法体。
     * getFieldErrors() 返回所有校验失败字段的列表，取第一个错误信息返回给前端。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), fieldError.getDefaultMessage());
    }

    /**
     * HTTP 请求方法不支持（405）。
     * 触发时机：前端用 GET 请求了一个只支持 POST 的接口（或反过来）。
     * getSupportedMethods() 返回接口实际支持的方法列表，拼成友好提示。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "请求方式错误，该接口仅支持 " + String.join("/", e.getSupportedMethods()));
    }

    /**
     * 兜底异常处理（预期外错误）。
     * 触发时机：所有未被上方精确匹配的异常（NullPointerException、数据库连接断开……）。
     * Exception 是所有异常的父类，Spring 按精确度优先匹配，没有更精确的才落到这里。
     * 只有这里才打 error 日志，表示出现了需要排查的 bug。
     * 返回通用错误信息而不是堆栈信息，避免内部实现细节暴露给前端（安全考虑）。
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ResultCode.ERROR.getMessage());
    }
}
