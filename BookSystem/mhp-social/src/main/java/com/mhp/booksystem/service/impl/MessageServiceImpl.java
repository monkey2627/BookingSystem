package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.MessageSendDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Message;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.mapper.MessageMapper;
import com.mhp.booksystem.service.MessageService;
import com.mhp.booksystem.vo.ConversationVO;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    private final AccountFeignClient accountFeignClient;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void send(MessageSendDTO dto) {
        Long fromUserId = StpUtil.getLoginIdAsLong();
        if (fromUserId.equals(dto.getToUserId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不能给自己发消息");
        }
        UserDTO toUser = accountFeignClient.getUser(dto.getToUserId()).getData();
        if (toUser == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        Message message = new Message();
        message.setFromUserId(fromUserId);
        message.setToUserId(dto.getToUserId());
        message.setContent(dto.getContent());
        message.setMsgType(dto.getMsgType());
        message.setIsRead(0);
        save(message);

        messagingTemplate.convertAndSendToUser(
                dto.getToUserId().toString(),
                "/queue/messages",
                toVO(message)
        );
    }

    @Override
    public CursorPageVO<MessageVO> history(Long targetUserId, Long lastId, int size) {
        Long userId = StpUtil.getLoginIdAsLong();

        List<Message> messages = lambdaQuery()
                .and(w -> w
                        .eq(Message::getFromUserId, userId).eq(Message::getToUserId, targetUserId)
                        .or()
                        .eq(Message::getFromUserId, targetUserId).eq(Message::getToUserId, userId)
                )
                .lt(lastId != null && lastId > 0, Message::getId, lastId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + (size + 1))
                .list();

        boolean hasMore = messages.size() > size;
        if (hasMore) messages = messages.subList(0, size);
        if (messages.isEmpty()) return CursorPageVO.of(List.of(), false, null);

        List<Long> unreadIds = messages.stream()
                .filter(m -> m.getFromUserId().equals(targetUserId) && m.getIsRead() == 0)
                .map(Message::getId)
                .collect(Collectors.toList());
        if (!unreadIds.isEmpty()) {
            Message update = new Message();
            update.setIsRead(1);
            update(update, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                    .in(Message::getId, unreadIds));
        }

        List<MessageVO> voList = messages.stream().map(this::toVO).collect(Collectors.toList());
        return CursorPageVO.of(voList, hasMore, messages.get(messages.size() - 1).getId());
    }

    @Override
    public List<ConversationVO> getConversations() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<ConversationVO> conversations = ((MessageMapper) baseMapper).selectConversations(userId);
        if (conversations == null || conversations.isEmpty()) return Collections.emptyList();

        // Fill in nickname and avatar from mhp-account via Feign
        List<Long> userIds = conversations.stream().map(ConversationVO::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> users = accountFeignClient.batchGetUsers(userIds).getData();
        Map<Long, UserDTO> userMap = users == null ? Collections.emptyMap()
                : users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        conversations.forEach(c -> {
            UserDTO user = userMap.get(c.getUserId());
            if (user != null) {
                c.setNickname(user.getNickname());
                c.setAvatar(user.getAvatar());
            }
        });
        return conversations;
    }

    private MessageVO toVO(Message m) {
        MessageVO vo = new MessageVO();
        vo.setId(m.getId());
        vo.setFromUserId(m.getFromUserId());
        vo.setToUserId(m.getToUserId());
        vo.setContent(m.getContent());
        vo.setMsgType(m.getMsgType());
        vo.setIsRead(m.getIsRead() != null && m.getIsRead() == 1);
        vo.setCreateTime(m.getCreateTime());
        return vo;
    }
}
