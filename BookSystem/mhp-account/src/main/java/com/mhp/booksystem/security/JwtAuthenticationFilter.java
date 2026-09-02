package com.mhp.booksystem.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器：从 Header "token" 中解析 JWT，成功则将 userId 写入 SecurityContext。
 *
 * 位置：addFilterBefore(UsernamePasswordAuthenticationFilter.class)，
 * 即在 Spring Security 的默认用户名/密码认证之前执行。
 *
 * 设计原则：filter 本身不决定 401，只负责"能解析就设 context"。
 * 路径是否需要登录由 SecurityConfig 的 authorizeHttpRequests 规则决定：
 *   - 白名单路径：即使 context 空，也能放行
 *   - 保护路径：context 空 → Security 触发 AuthenticationEntryPoint → 返回 401
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("token");
        if (token != null) {
            try {
                Long userId = JwtUtil.parse(token);
                // Principal 直接存 userId（Long），与原 StpUtil.getLoginIdAsLong() 语义等价
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
            } catch (Exception ignored) {
                // token 非法或已过期：不设置 context，Security 后续会拦截受保护路径
            }
        }
        chain.doFilter(request, response);
    }
}
