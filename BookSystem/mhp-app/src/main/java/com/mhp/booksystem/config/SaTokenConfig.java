package com.mhp.booksystem.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 登录拦截器配置
 *
 * ── 什么是拦截器（Interceptor）──
 * SpringMVC 的拦截器，在 Controller 方法执行之前/之后做预处理。
 * 请求链路：请求进来 → Filter过滤器 → Interceptor拦截器 → Controller → 返回响应
 *   - Filter：Servlet 容器层，在进入 Spring 之前执行，可修改 request/response 流
 *   - Interceptor：SpringMVC 内部，只作用于 Controller 接口，不能修改原生请求流
 * 典型用途：登录校验、权限校验、日志打印、请求耗时统计
 *
 * ── HandlerInterceptor 原生三个执行时机 ──
 *   preHandle()       Controller 执行【之前】，返回 false 直接截断，Controller 不会执行
 *   postHandle()      Controller 执行完、视图渲染前；抛异常则跳过；前后端分离项目基本用不到
 *   afterCompletion() 请求彻底结束，无论是否抛异常都会执行，适合日志统计、ThreadLocal 清理
 * SaInterceptor 底层实现了 HandlerInterceptor，登录校验逻辑写在 preHandle 里。
 *
 * ── 为什么实现 WebMvcConfigurer ──
 * WebMvcConfigurer 是 SpringMVC 提供的配置回调接口，里面是一堆空的默认方法：
 *   addInterceptors()   注册拦截器
 *   addCorsMappings()   配置跨域
 *   ... 等
 * Spring 启动时会找到所有实现了 WebMvcConfigurer 的 @Configuration Bean，
 * 回调执行它们的方法，把配置合并进 SpringMVC 上下文。
 *
 * 注意：只实现接口不会产生拦截效果，必须在 addInterceptors() 里调用
 * registry.addInterceptor() 把拦截器实例交给 Spring 注册才会生效。
 * 不要加 @EnableWebMvc，否则会抛弃 SpringBoot 的自动 MVC 配置，很多功能失效。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 登录拦截器
     *
     * 请求进来后的完整流程：
     *   1. 进入 SaInterceptor.preHandle()
     *   2. SaRouter 判断当前路径是否在 notMatch 白名单里
     *   3. 白名单路径 → 直接放行，走到 Controller
     *   4. 其他路径 → 执行 StpUtil.checkLogin() 校验 token
     *   5. token 合法 → 放行；未登录 → 抛 NotLoginException → GlobalExceptionHandler 返回 401
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle ->
                // 匹配所有请求路径
                SaRouter.match("/**")
                        // 白名单：不需要登录校验的路径
                        // /ws/**：WebSocket 握手请求（长连接建立后本身不走 MVC 拦截器）
                        // /swagger-ui/** 和 /v3/api-docs/**：开发阶段接口文档，否则打开 Swagger 也会 401
                        .notMatch(
                                "/api/user/login",
                                "/api/user/register",
                                "/ws/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        )
                        // 命中（非白名单）的请求执行登录校验，未登录抛异常
                        .check(r -> StpUtil.checkLogin())
        // addPathPatterns("/**")：让此拦截器对所有 URL 生效
        // 与上面 SaRouter.match("/**") 看似重复，但职责不同：
        //   addPathPatterns 是 SpringMVC 决定"要不要进这个拦截器"
        //   SaRouter.match  是进了拦截器后"要不要执行校验逻辑"
        )).addPathPatterns("/**");
    }
}
