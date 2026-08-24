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

/**
 * STOMP 连接认证拦截器 — 在 WebSocket 握手阶段验证 Sa-Token。
 *
 * 为什么不用 HTTP 拦截器（SaTokenConfig）？
 *   WebSocket 连接是通过 HTTP Upgrade 建立的，连接一旦升级为 WS 协议，
 *   后续帧不再走 Spring MVC 的 HandlerInterceptor。
 *   STOMP 的 CONNECT 帧是 WS 建立后的第一帧，需要在消息通道层拦截。
 *
 * 执行时机：
 *   客户端发送 STOMP CONNECT 帧 → Spring 推入 inboundChannel
 *   → 本拦截器 preSend() 被调用 → 验证通过则设置 user，否则抛异常断开连接。
 *
 * accessor.setUser() 的作用：
 *   Spring 的 UserDestinationResolver 依靠 Principal.getName() 来路由
 *   /user/{userId}/queue/xxx 消息，必须在这里把 userId 注入为 Principal，
 *   后续 convertAndSendToUser(userId, ...) 才能找到正确的会话。
 */
@Slf4j
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // 只拦截 CONNECT 帧，其他帧（SUBSCRIBE/SEND/DISCONNECT）直接放行
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        // token 由前端在 STOMP CONNECT 帧的 native header 中携带
        // 对应前端 useWebSocket.ts 中的 connectHeaders: { token: userStore.token }
        String token = accessor.getFirstNativeHeader("token");
        if (token == null || token.isBlank()) {
            throw new MessagingException("WebSocket 连接未携带 token");
        }

        // Sa-Token 根据 token 字符串反查 loginId（不依赖 ThreadLocal，线程安全）
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            throw new MessagingException("WebSocket token 无效或已过期");
        }

        // 将 userId 注入为 Principal，后续推消息时通过 userId 找到对应的 WS 会话
        final String userId = loginId.toString();
        accessor.setUser(() -> userId);
        log.debug("[WS] 用户 {} 建立 WebSocket 连接", userId);
        return message;
    }
}
