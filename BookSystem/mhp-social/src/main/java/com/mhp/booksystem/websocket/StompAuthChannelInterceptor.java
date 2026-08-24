package com.mhp.booksystem.websocket;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = accessor.getFirstNativeHeader("token");
        if (token == null || token.isBlank()) {
            throw new MessagingException("WebSocket 连接未携带 token");
        }

        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            throw new MessagingException("WebSocket token 无效或已过期");
        }

        final String userId = loginId.toString();
        accessor.setUser(() -> userId);
        log.debug("[WS] 用户 {} 建立 WebSocket 连接", userId);
        return message;
    }
}
