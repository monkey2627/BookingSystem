package com.mhp.booksystem.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截器配置（mhp-social）。
 *
 * 完整机制说明（@Configuration 扫描、WebMvcConfigurer 钩子、Tomcat 线程池、ThreadLocal 生命周期）
 * 参见 mhp-account 模块的 SaTokenConfig.java，注释更详尽。
 *
 * 本服务白名单：
 *   /internal/**     内部 Feign 接口，无 token
 *   /ws/**           WebSocket 握手请求（STOMP 连接建立走 StompAuthChannelInterceptor 验证，
 *                    不走 HTTP 拦截器；但 SockJS fallback 的 HTTP 握手也需要放行）
 *   /swagger-ui/**   Swagger UI 静态资源
 *   /v3/api-docs/**  OpenAPI 文档 JSON
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            SaRouter.match("/**")
                    .notMatch(
                            "/internal/**",
                            "/ws/**",
                            "/swagger-ui/**",
                            "/v3/api-docs/**"
                    )
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/**");
    }
}
