package com.mhp.booksystem.common;

import lombok.Data;

/**
 * 统一 HTTP 响应体
 *
 * 所有接口都返回此结构，前端只需在 Axios 拦截器里统一判断 code：
 *   code=200  → 成功，取 data
 *   code=401  → 未登录，跳转登录页
 *   其他      → 业务错误，弹 message
 *
 * 泛型 T 是业务数据的类型，查询接口传具体 VO，无数据时传 null。
 */
@Data
public class Result<T> {

    /** 业务状态码，200=成功，其他见 ResultCode */
    private Integer code;

    /** 提示信息，成功时为"成功"，失败时为具体错误原因 */
    private String message;

    /** 业务数据，失败时固定为 null */
    private T data;

    /** 构造方法私有，强制通过静态工厂方法创建，防止外部随意构造 */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**静态方法用来创造一个结果对象 成功并携带数据，如：return Result.ok(merchantVO) */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 成功但无数据返回，如：增删改操作 */
    public static Result<?> ok() {
        return ok(null);
    }

    /** 失败，手动指定 code 和 message */
    public static Result<?> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败，使用默认错误码 500，如：throw 到 GlobalExceptionHandler 兜底时 */
    public static Result<?> fail(String message) {
        return fail(ResultCode.ERROR.getCode(), message);
    }

    /** 失败，直接传 ResultCode 枚举，最常用：Result.fail(ResultCode.SCHEDULE_FULL) */
    public static Result<?> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }
}
