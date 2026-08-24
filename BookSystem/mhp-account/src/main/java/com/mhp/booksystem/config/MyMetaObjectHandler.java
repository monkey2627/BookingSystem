package com.mhp.booksystem.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充处理器。
 *
 * 配合实体类上的注解使用：
 *   @TableField(fill = FieldFill.INSERT)         → insertFill() 时填充
 *   @TableField(fill = FieldFill.INSERT_UPDATE)  → insertFill() 和 updateFill() 时填充
 *
 * 好处：createTime / updateTime 无需在每个 Service 手动 set，由框架统一处理。
 * strictInsertFill 只有在字段当前为 null 时才填充（strict），
 * 防止外部传入的非 null 值被覆盖。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
