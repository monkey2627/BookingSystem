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
 * WebSocket + STOMP 配置。
 *
 * 端点：/ws（SockJS 降级）
 *   前端通过 new SockJS('/ws') 连接，若浏览器不支持 WS，SockJS 自动降级到
 *   xhr-streaming 或 xhr-polling，连接过程对业务代码透明。
 *
 * 消息代理（Simple Broker，内存级）：
 *   /topic — 广播，任何订阅了某个 topic 的客户端都能收到（本项目未用）
 *   /user  — 单播，Spring 内部用 userId 路由，只有目标用户的会话能收到
 *
 * 应用目的地前缀 /app：
 *   客户端向 /app/xxx 发消息 → 路由到 @MessageMapping("/xxx") 的方法处理（本项目未用）
 *   本项目消息全部走 HTTP REST → Service → WebSocket push，不需要客户端直接发 STOMP 消息。
 *
 * 用户目的地前缀 /user：
 *   convertAndSendToUser("123", "/queue/messages", payload)
 *   实际发到 /user/123/queue/messages，只有 userId=123 的连接能收到。
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // 开发阶段放开跨域，生产应收紧为具体域名
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/user");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 将认证拦截器注册到入站通道，CONNECT 帧在此验证 token
        registration.interceptors(stompAuthInterceptor);
    }
}
