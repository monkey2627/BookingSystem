package com.mhp.booksystem.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截器配置（mhp-booking）。
 *
 * 完整机制说明（@Configuration 扫描、WebMvcConfigurer 钩子、Tomcat 线程池、ThreadLocal 生命周期）
 * 参见 mhp-account 模块的 SaTokenConfig.java，注释更详尽。
 *
 * 本服务白名单（登录/注册由 mhp-account 提供，booking 服务没有这两个路径）：
 *   /internal/**     内部 Feign 接口，不经过网关，无 token
 *   /swagger-ui/**   Swagger UI 静态资源
 *   /v3/api-docs/**  OpenAPI 文档 JSON
 * 其余所有路径均需登录校验。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle ->
                SaRouter.match("/**")
                        .notMatch(
                                "/internal/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        )
                        .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");
    }
}
