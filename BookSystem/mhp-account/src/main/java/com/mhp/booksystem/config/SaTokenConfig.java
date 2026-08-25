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
 * ── @Configuration 如何被 Spring 发现 ─────────────────────────────────────
 * @SpringBootApplication 包含 @ComponentScan，启动时扫描主启动类所在包及其子包下的所有类。
 * 凡是标了 @Configuration 的类，Spring 都会把它注册为 Bean，并在初始化 MVC 时调用其钩子方法。
 * 你不需要主动"引用"这个类——Spring 自己扫描到就会处理。
 *
 * ── WebMvcConfigurer 是什么 ────────────────────────────────────────────────
 * Spring MVC 提供的回调接口（共约 20 个空默认方法），每个方法对应一个 MVC 扩展点：
 *   addInterceptors()   → 注册拦截器（本类的用途）
 *   addCorsMappings()   → 配置跨域
 *   addResourceHandlers → 静态资源映射
 *   ……
 * Spring 启动时找到所有实现了 WebMvcConfigurer 的 @Configuration Bean，
 * 依次回调它们的方法来组装 MVC 配置。实现这个接口就是在告诉 Spring "我要定制 MVC"。
 * 注意：不要加 @EnableWebMvc，否则会丢弃 Boot 的 MVC 自动配置。
 *
 * ── 拦截器的执行位置 ──────────────────────────────────────────────────────
 * DispatcherServlet（Spring MVC 的总前端控制器）收到请求后：
 *   1. 构建 HandlerExecutionChain = Controller方法 + 所有已注册拦截器
 *   2. 按注册顺序调用每个拦截器的 preHandle()（返回 false 则直接截断，不进 Controller）
 *   3. 调用 Controller 方法体
 *   4. 按逆序调用每个拦截器的 afterCompletion()（无论是否抛异常都执行）
 *
 * ── SaInterceptor.preHandle() 做了什么 ────────────────────────────────────
 * 1. 从 HTTP Header "token" 取出 token 字符串（配置项 sa-token.token-name=token）。
 * 2. 用 token 查 Redis：Redis 存有 token→userId 记录，无记录或已过期 → 抛 NotLoginException。
 * 3. 验证通过 → 将 userId 写入当前线程的 ThreadLocal（Sa-Token 内部机制）。
 *    此后本次请求内任何代码调用 StpUtil.getLoginIdAsLong() 都直接从 ThreadLocal 读，
 *    O(1) 获取当前用户 id，不再查 Redis。
 *
 * ── Tomcat 线程池与 ThreadLocal 生命周期 ──────────────────────────────────
 * Tomcat 维护一个线程池（默认 10~200 条线程）。每个 HTTP 请求到达时从池中取一条线程，
 * 该线程全程负责处理：preHandle → Controller → Service → afterCompletion → 归还给池。
 *   • 同一用户的不同请求 → 不同线程（池里随机分配，不固定）。
 *   • ThreadLocal = 当前线程的私有存储槽，生命周期 = 一次请求，不跨请求。
 *   • afterCompletion() 中 Sa-Token 自动清除 ThreadLocal，防止线程复用时污染下一个请求。
 *
 * ── 多服务共享 token ────────────────────────────────────────────────────
 * Sa-Token 的 token 存在 Redis（sa-token-redis-jackson 整合），
 * 三个微服务共享同一个 Redis，所以一个 token 在所有服务上都有效，
 * 无需网关统一鉴权或 JWT 自包含。
 *
 * 白名单（不需要登录的路径）：
 *   /api/user/login    登录接口（本服务提供）
 *   /api/user/register 注册接口（本服务提供）
 *   /internal/**       内部 Feign 接口，不经过网关，无 token
 *   /swagger-ui/**     Swagger UI 静态资源
 *   /v3/api-docs/**    OpenAPI 文档 JSON
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    // @Override：编译器验证此方法确实存在于接口中（Java 6+ 起适用于接口方法实现）。
    // 拼错方法名会立即报错，是防御性编程的好习惯。
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // SaInterceptor：Sa-Token 提供的标准 HandlerInterceptor 实现，校验逻辑在 preHandle() 里。
        // 构造参数是 lambda，每次请求命中时执行：
        //   SaRouter.match("/**")  匹配所有路径
        //   .notMatch(...)         白名单路径直接放行，不执行 check
        //   .check(StpUtil::checkLogin)  验证 token，失败抛 NotLoginException
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
        // addPathPatterns("/**")：SpringMVC 层面决定"要不要进这个拦截器"。
        // 与 SaRouter.match("/**") 看似重复，但职责不同：
        //   addPathPatterns 是 SpringMVC 的过滤条件（进不进拦截器）
        //   SaRouter.match  是进了拦截器后"要不要执行校验逻辑"（可精细排除白名单）
        )).addPathPatterns("/**");
    }
}
