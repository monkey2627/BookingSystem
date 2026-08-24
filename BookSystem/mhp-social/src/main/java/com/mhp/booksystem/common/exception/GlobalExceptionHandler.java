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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLogin(NotLoginException e) {
        return Result.fail(ResultCode.UNAUTHORIZED);
    }

    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldErrors().get(0);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), fieldError.getDefaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "请求方式错误，该接口仅支持 " + String.join("/", e.getSupportedMethods()));
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ResultCode.ERROR.getMessage());
    }
}
