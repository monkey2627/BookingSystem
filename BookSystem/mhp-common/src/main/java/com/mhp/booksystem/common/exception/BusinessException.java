package com.mhp.booksystem.common.exception;

import com.mhp.booksystem.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常 — 所有"预期内"的错误都用这个抛出。
 *
 * 区分"预期内"和"预期外"：
 *   - 预期内（BusinessException）：档期不存在、重复预约、权限不足……
 *     GlobalExceptionHandler 捕获后返回对应 code+message，不打 error 日志。
 *   - 预期外（Exception）：NullPointerException、数据库连接断开……
 *     GlobalExceptionHandler 兜底，打 error 日志 + 返回 500。
 *
 * 使用方式：
 *   throw new BusinessException(ResultCode.BOOKING_NOT_FOUND);
 *   throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "档期日期不能早于今天");
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    /** 仅有消息，code 默认 500（兜底用，尽量用下面两个构造方法） */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    /** 自定义 code + message，适合在 ResultCode 里没有完全匹配的场景 */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /** 最常用：直接传 ResultCode 枚举，code 和 message 一起带走 */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }
}
