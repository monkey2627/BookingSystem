package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.MessageSendDTO;
import com.mhp.booksystem.entity.Message;
import com.mhp.booksystem.vo.ConversationVO;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.MessageVO;

import java.util.List;

public interface MessageService extends IService<Message> {

    void send(MessageSendDTO dto);

    CursorPageVO<MessageVO> history(Long targetUserId, Long lastId, int size);

    List<ConversationVO> getConversations();
}
