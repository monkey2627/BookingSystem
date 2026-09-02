package com.mhp.booksystem.config;

import com.mhp.booksystem.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（mhp-social）。
 *
 * 白名单：
 *   /internal/**     内部 Feign 接口，不携带 token
 *   /ws/**           WebSocket 握手（STOMP 认证由 StompAuthChannelInterceptor 单独处理）
 *   /api/post/feed   动态广场，未登录也可浏览（可选认证，controller 内判断 context）
 *   /swagger-ui/**   Swagger UI
 *   /v3/api-docs/**  OpenAPI JSON
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/internal/**",
                                "/ws/**",
                                "/api/post/feed",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":40001,\"msg\":\"请先登录\",\"data\":null}");
                }))
                .build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("JWT-only mode");
        };
    }
}
