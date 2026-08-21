package com.mhp.booksystem.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置
 *
 * 什么是跨域？
 *   浏览器的"同源策略"规定：A 页面只能请求和自己同协议 + 同域名 + 同端口的接口。
 *   本项目开发时：前端 localhost:5173（Vite），后端 localhost:8080（Spring Boot）——端口不同，跨域。
 *   浏览器会在发送真实请求前先发一个 OPTIONS 预检请求，后端必须明确表示"我允许你跨域"，
 *   浏览器才会放行真正的请求。
 *
 * 开发时不是用 Vite proxy 解决了吗？为什么还要配这里？
 *   Vite proxy 是"开发专用的障眼法"：前端把 /api 请求发给自己的 5173 端口，
 *   Vite 在服务端把请求转发给 8080，浏览器全程看到的都是 5173，自然没有跨域。
 *   但有几个场景 Vite proxy 帮不了你：
 *     1. 生产环境用 Nginx 反向代理但配置不当时（同域则不需要，但配错了就会跨域）
 *     2. 用 Postman/Apifox 直接调后端接口调试
 *     3. 将来开发小程序/App，直接请求后端 IP，没有 proxy 层
 *   所以后端自己配好 CORS，任何客户端都能无障碍使用。
 *
 * allowedOriginPatterns("*") vs allowedOrigins("*")
 *   Spring Boot 3 中，allowCredentials=true（允许携带 Cookie/Header）和 allowedOrigins("*")
 *   不能同时使用（安全限制），必须改用 allowedOriginPatterns("*")。
 *   本项目用 token Header 认证，理论上 allowCredentials 可以不开，
 *   但保留 allowedOriginPatterns 是最兼容的写法。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")          // 只对 /api/ 开头的接口允许跨域，其他路径不受影响
                .allowedOriginPatterns("*")     // 允许任何来源（生产环境可改成具体域名，如 "https://your-domain.com"）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")  // OPTIONS 是浏览器预检请求，必须放行
                .allowedHeaders("*")            // 允许所有请求头，包括我们自定义的 "token" header
                .exposedHeaders("token")        // 允许前端 JS 读取响应中的 token header（非简单响应头默认不暴露）
                .maxAge(3600);                  // 预检结果缓存 1 小时，避免每次请求都发 OPTIONS，减少开销
    }
}
