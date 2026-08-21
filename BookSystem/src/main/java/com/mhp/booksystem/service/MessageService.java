package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.MessageSendDTO;
import com.mhp.booksystem.entity.Message;
import com.mhp.booksystem.vo.ConversationVO;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.MessageVO;

import java.util.List;

public interface MessageService extends IService<Message> {

    /** 发送消息 */
    void send(MessageSendDTO dto);

    /** 查与某用户的历史消息（游标分页，按 id 倒序） */
    CursorPageVO<MessageVO> history(Long targetUserId, Long lastId, int size);

    /** 获取当前用户的会话列表（每个对话方最后一条消息 + 未读数） */
    List<ConversationVO> getConversations();
}
