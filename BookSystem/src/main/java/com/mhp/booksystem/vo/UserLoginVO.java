package com.mhp.booksystem.vo;

import lombok.Data;

/**
 * VO（View Object）— 后端返回给前端的数据载体
 *
 * 传输方向：Service 组装 → Controller 包进 Result → 序列化成 JSON → 前端
 *
 * 为什么不直接返回 Entity（User）？
 *   1. 安全：User 实体含有 password 字段，直接返回会把密码暴露给前端。
 *   2. 字段不匹配：登录成功需要返回 token，但 User 表里没有这个字段，
 *      Entity 无法承载这种"跨来源"的组合数据。
 *
 * 前端拿到 token 后存入 localStorage，
 * 后续每次请求在 Header 里带上：token: xxxxxx
 */
@Data
public class UserLoginVO {

    private String token;

    private Long userId;

    private String nickname;

    private String avatar;

    /** 是否已开通卖家服务（Merchant 表有对应记录） */
    private Boolean hasMerchantProfile;
}
