package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mhp.booksystem.entity.Message;
import com.mhp.booksystem.vo.ConversationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    List<ConversationVO> selectConversations(@Param("userId") Long userId);
}
