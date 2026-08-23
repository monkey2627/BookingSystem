package com.mhp.booksystem.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器
 *
 * MetaObjectHandler 是 MyBatis-Plus 提供的接口，预留了一个扩展点：
 * 框架在执行 insert/update 前会来 Spring 容器里找有没有实现这个接口的 Bean，
 * 找到就调对应的方法，由我们决定填什么值。
 *
 * Entity 字段上的 @TableField(fill = FieldFill.INSERT) 只是标记"这个字段需要填充"，
 * 真正填什么由这里决定，两者配合才能生效。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 执行 insert 时触发
     * metaObject 是当前要插入的实体对象（如 user、merchant）被框架包装后的形态，
     * 框架用它来反射读写字段，我们原样传进去即可。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // 参数1：被插入的实体对象（框架包装后）
        // 参数2：Java 字段名，必须与 Entity 里的字段名完全一致
        // 参数3：字段类型，用于匹配，防止同名不同类型的字段被误填
        // 参数4：要填入的值
        // strict 版本：字段名、类型、当前值为 null、有 fill 标记，四个条件全满足才填，不会覆盖手动赋的值
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /**
     * 执行 update 时触发
     * 只更新 updateTime，createTime 不动
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
