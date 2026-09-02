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
 * Spring Security 配置（mhp-account）。
 *
 * 替换原 SaTokenConfig（WebMvcConfigurer + SaInterceptor）。
 * 核心原则：无状态 JWT，不依赖 Session 和 Cookie。
 *
 * 白名单：
 *   /api/user/login      登录接口（本服务提供）
 *   /api/user/register   注册接口（本服务提供）
 *   /internal/**         内部 Feign 接口，不经过网关，不携带 token
 *   /swagger-ui/**       Swagger UI
 *   /v3/api-docs/**      OpenAPI JSON
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
                                "/api/user/login",
                                "/api/user/register",
                                "/internal/**",
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

    /**
     * 声明空实现，防止 Spring Boot 打印自动生成密码的警告日志。
     * 本项目使用 JWT 鉴权，不走 UserDetailsService 体系。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("JWT-only mode");
        };
    }
}
