package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mhp.booksystem.entity.IkCustomWord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IkCustomWordMapper extends BaseMapper<IkCustomWord> {

    /**
     * 查询指定类型（扩展词/停止词）所有生效词条的文本列表。
     * IK 词库接口直接将返回列表拼成纯文本（每行一词）。
     */
    @Select("SELECT word FROM ik_custom_word WHERE type = #{type} AND status = 1 ORDER BY id")
    List<String> selectActiveWords(@Param("type") int type);

    /**
     * 查询指定类型最近一次更新时间，用作 HTTP Last-Modified 响应头。
     * IK 客户端对比此值与上次拉取时间，无变化时跳过词库重载。
     * 返回 null 表示该类型下没有生效词条。
     */
    @Select("SELECT MAX(update_time) FROM ik_custom_word WHERE type = #{type} AND status = 1")
    LocalDateTime selectMaxUpdateTime(@Param("type") int type);
}
