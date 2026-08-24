package com.mhp.booksystem.common;

import lombok.Getter;

/**
 * 业务错误码枚举
 *
 * 分段设计，方便通过 code 定位问题所在模块：
 *   2xx      通用（遵循 HTTP 语义）
 *   1xxxx    档期模块（Schedule/Rush）
 *   2xxxx    预约模块（Booking）
 *   3xxxx    用户模块（User）
 *   4xxxx    商家模块（Merchant）
 *   5xxxx    评价模块（Review）
 *   6xxxx    动态模块（Post）
 *   7xxxx    问卷模块（Questionnaire）
 *   8xxxx    投诉模块（Complaint）
 *   9xxxx    文件上传
 *
 * 新增错误码时在对应分段追加，不要修改已有枚举的 code 值（前端可能已硬编码判断）。
 */
@Getter
public enum ResultCode {

    // ==================== 通用 ====================
    SUCCESS(200, "成功"),
    ERROR(500, "服务器内部错误"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    UNAUTHORIZED(401, "未登录"),
    FORBIDDEN(403, "无权限"),
    PARAM_ERROR(400, "参数错误"),

    // ==================== 档期模块 10xxx ====================
    SCHEDULE_FULL(10001, "排队人数已满"),
    SCHEDULE_NOT_OPEN(10002, "抢购尚未开始"),
    SCHEDULE_NOT_FOUND(10003, "档期不存在"),
    SCHEDULE_NOT_AVAILABLE(10004, "档期不可用"),
    SCHEDULE_DUPLICATE(10005, "同一天该时间段已存在档期"),
    RUSH_ALREADY_JOINED(10006, "您已在排队中"),

    // ==================== 预约模块 20xxx ====================
    BOOKING_DUPLICATE(20001, "您已预约该档期"),
    BOOKING_NOT_FOUND(20002, "预约不存在"),
    BOOKING_STATUS_ERROR(20003, "预约状态异常"),

    // ==================== 用户模块 30xxx ====================
    USER_NOT_FOUND(30001, "用户不存在"),
    USER_PHONE_EXISTS(30002, "手机号已注册"),
    USER_NOT_MERCHANT(30003, "当前账号不是商家"),

    // ==================== 商家模块 40xxx ====================
    MERCHANT_NOT_FOUND(40001, "商家不存在"),

    // ==================== 评价模块 50xxx ====================
    REVIEW_ALREADY_EXISTS(50001, "该预约已评价"),
    REVIEW_NOT_FOUND(50002, "评价不存在"),
    REVIEW_BOOKING_NOT_COMPLETE(50003, "预约未完成，暂不能评价"),

    // ==================== 动态模块 60xxx ====================
    POST_NOT_FOUND(60001, "动态不存在"),

    // ==================== 问卷模块 70xxx ====================
    QUESTIONNAIRE_NOT_FOUND(70001, "问卷模板不存在"),

    // ==================== 投诉模块 80xxx ====================
    COMPLAINT_BOOKING_NOT_FOUND(80001, "关联预约不存在"),

    // ==================== 文件上传 90xxx ====================
    UPLOAD_FILE_TYPE_ERROR(90001, "仅支持 jpg/png/webp 格式"),
    UPLOAD_FILE_TOO_LARGE(90002, "文件大小不能超过 5MB");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
