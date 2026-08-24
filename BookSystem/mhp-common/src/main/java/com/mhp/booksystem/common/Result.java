package com.mhp.booksystem.common;

import lombok.Data;//用来自动生成类所需要的getter，setter等

/**
 * 统一返回给前端的数据格式，无论前端什么请求，最终后端都会返回给
 * 前端一个Result类型的数据，前端只需在 Axios 拦截器里统一判断 code
 * 不同的code约定不同的情况，对应处理即可
 * 泛型T是业务数据的类型，查询接口传具体 VO，无数据时传 null。
 */
@Data
public class Result<T> {

    /** 业务状态码，详细定义见ResultCode */
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

    /**用来创造一个携带了返回数据的结果对象
     * 注意，这里涉及到静态方法的泛型知识，静态方法的T和类上的T毫无关联仅仅是名称一样，类上的T只对实例相关有效
     * */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 成功但无数据返回，如：增删改操作 */
    public static Result<?> ok() {
        return ok(null);
    }

    /** 失败，手动指定 code 和 message */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败，使用默认错误码 500，如：throw 到 GlobalExceptionHandler 兜底时 */
    public static <T> Result<T> fail(String message) {
        return fail(ResultCode.ERROR.getCode(), message);
    }

    /** 失败，直接传 ResultCode 枚举，最常用：Result.fail(ResultCode.SCHEDULE_FULL) */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }
}
