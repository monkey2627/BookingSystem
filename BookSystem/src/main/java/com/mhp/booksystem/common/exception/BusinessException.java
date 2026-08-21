package com.mhp.booksystem.common.exception;

import com.mhp.booksystem.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 *
 * 用于 Service 层主动抛出的可预期错误，如档期已满、重复下单等。
 * 与普通 RuntimeException 的区别：携带 code 字段，GlobalExceptionHandler
 * 捕获后直接用 code+message 构造 Result 返回，不打 error 日志（因为是正常业务流程）。
 *
 * 用法：
 *   throw new BusinessException(ResultCode.SCHEDULE_FULL);
 *   throw new BusinessException("自定义错误信息");
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 对应 ResultCode 中的 code，会原样返回给前端 */
    private final Integer code;

    /** 自定义错误信息，使用默认错误码 500 */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    /** 使用预定义错误码，推荐方式 */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }
}
