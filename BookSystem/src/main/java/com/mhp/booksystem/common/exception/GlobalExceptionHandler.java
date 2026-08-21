package com.mhp.booksystem.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 *
 * 拦截所有 Controller 抛出的异常，统一转为 Result 格式返回。
 * 保证接口永远不会把堆栈信息暴露给前端。
 *
 * 处理优先级（从高到低）：
 *   1. BusinessException     → 业务错误，直接取 code+message 返回，不打日志
 *   2. MethodArgumentNotValidException → @Valid 校验失败，提取第一个字段错误信息
 *   3. Exception             → 未预期异常，打 error 日志，返回通用错误提示
 *
 * 优先级由异常类型的继承关系决定，Spring 自动匹配最精确的处理器：
 *   - BusinessException 精确匹配 handleBusiness，不会落到 handleException
 *   - 其他未知异常向上找父类，最终才匹配 Exception 兜底
 *   - 同一类型不能注册两个处理器，启动时会报 Ambiguous @ExceptionHandler
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理未登录异常
     * SaInterceptor 执行 StpUtil.checkLogin() 失败时抛出此异常。
     * 返回 401，前端 Axios 响应拦截器统一捕获 401 后跳转到登录页，
     * 后端不直接重定向——前后端分离项目后端不知道前端部署地址。
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLogin(NotLoginException e) {
        return Result.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 处理业务异常
     * Service 层 throw new BusinessException(...) 最终都会到这里
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验失败
     * Controller 方法参数上加了 @Valid 且入参不合法时触发
     * 只取第一个校验失败的字段错误信息，避免一次返回过多错误
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), fieldError.getDefaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "请求方式错误，该接口仅支持 " + String.join("/", e.getSupportedMethods()));
    }

    /**
     * 兜底处理所有未预期异常
     * 打印完整堆栈用于排查，但只向前端返回通用错误提示，不暴露内部细节
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ResultCode.ERROR.getMessage());
    }
}
