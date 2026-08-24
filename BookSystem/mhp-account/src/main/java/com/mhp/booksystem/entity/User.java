package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户表 — 所有人（客人和商家）共用同一张表，不再区分角色。
 *
 * 设计说明：
 *   - 历史版本曾有 role 字段（0=客人 1=商家），已通过 migration.sql 删除。
 *   - 是否是商家由 merchant 表有无对应记录决定（hasProfile），而非 user.role。
 *   - 这样一个人既可以作为客人预约别人，也可以填资料成为商家被别人预约。
 */
@Data
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号作为唯一登录凭证，注册时校验唯一性 */
    private String phone;

    /** MD5 加密存储（Hutool SecureUtil.md5），不存明文 */
    private String password;

    private String nickname;

    /** 头像 URL，存七牛云 CDN 链接 */
    private String avatar;

    /** 0=未填 1=男 2=女 3=其他 */
    private Integer gender;

    private LocalDate birthday;

    /** MyBatisPlusConfig 中配置自动填充，INSERT 时赋当前时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** INSERT 和 UPDATE 时自动赋当前时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志：0=正常 1=已删除。
     * MyBatis-Plus 会在所有查询自动加 WHERE is_deleted=0，
     * deleteById 实际执行 UPDATE ... SET is_deleted=1，不物理删除数据。
     */
    @TableLogic
    private Integer isDeleted;
}
