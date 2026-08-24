package com.mhp.booksystem.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截器配置（mhp-account）。
 *
 * 工作原理：
 *   请求进入 → SaInterceptor.preHandle() → SaRouter 匹配路径 → checkLogin()
 *   token 存在于 HTTP Header "token"（sa-token.token-name=token），
 *   Sa-Token 从 Header 取值，在 Redis 中验证有效性，无效则抛 NotLoginException，
 *   GlobalExceptionHandler 捕获后返回 code=401。
 *
 * 白名单（不需要登录的路径）：
 *   /api/user/login    登录接口
 *   /api/user/register 注册接口
 *   /internal/**       内部 Feign 接口，不经过网关，无 token
 *   /swagger-ui/**     Swagger UI 静态资源
 *   /v3/api-docs/**    OpenAPI 文档 JSON
 *
 * 多服务共享 token：
 *   Sa-Token 的 token 存在 Redis（sa-token-redis-jackson 整合），
 *   三个微服务共享同一个 Redis，所以一个 token 在所有服务上都有效，
 *   无需网关统一鉴权或 JWT 自包含。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle ->
                SaRouter.match("/**")
                        .notMatch(
                                "/api/user/login",
                                "/api/user/register",
                                "/internal/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        )
                        .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");
    }
}
