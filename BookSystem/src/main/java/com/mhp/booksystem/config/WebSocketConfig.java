package com.mhp.booksystem.config;

import com.mhp.booksystem.websocket.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over SockJS WebSocket 配置。
 *
 * 前端连接流程：
 *   1. new SockJS('/ws') 发起 HTTP 握手（Sa-Token 拦截器已放行 /ws/**）
 *   2. 发送 STOMP CONNECT 帧，携带 { token: xxx } 请求头
 *   3. StompAuthChannelInterceptor 验证 token，将 userId 设为 Principal
 *   4. 订阅 /user/queue/notify（系统通知）和 /user/queue/messages（聊天消息）
 *
 * 推送路径说明：
 *   convertAndSendToUser(userId, "/queue/notify", payload)
 *   → 实际路由到 /user/{userId}/queue/notify
 *   → 只有 Principal.getName() == userId 的连接才能收到
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 内存 broker，支持 /topic（广播）和 /user（点对点）两种前缀
        config.enableSimpleBroker("/topic", "/user");
        // 客户端发送消息到服务端的前缀（本项目暂不使用，保留扩展性）
        config.setApplicationDestinationPrefixes("/app");
        // 点对点目的地前缀，convertAndSendToUser 会自动拼接
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }
}
