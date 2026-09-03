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
 * Spring Security 核心配置（mhp-booking）。
 *
 * 整体策略：无状态 JWT 鉴权。
 *   - 不使用 Session / Cookie，每个请求通过 Header "token" 携带 JWT 自证身份。
 *   - token 验证在 JwtAuthenticationFilter 里完成，验证通过后把 userId 写入
 *     SecurityContextHolder，后续 Filter 和 Controller 层均可通过
 *     SecurityUtil.getCurrentUserId() 读取，O(1) 无 IO。
 *
 * 白名单（permitAll）：
 *   /internal/**         内部 Feign/Dubbo 接口，由其他微服务直接调用，不经过 Gateway，不携带 token
 *   /swagger-ui/**       Swagger 静态资源
 *   /swagger-ui.html     Swagger 入口页
 *   /v3/api-docs/**      OpenAPI JSON，Swagger UI 加载时请求
 * 其余所有路径（档期、预约、问卷）均需登录。
 */
@Configuration
@EnableWebSecurity  // 启用 Spring Security Web 支持，接管 Servlet Filter 链
public class SecurityConfig {

    /**
     * 将 JwtAuthenticationFilter 声明为 Bean。
     *
     * 为什么不直接 new JwtAuthenticationFilter() 写在 filterChain 里？
     * 声明为 Bean 后，Spring 会把它纳入容器管理，
     * 若 Filter 内部需要 @Autowired 其他 Bean，Spring 可以完成注入；
     * 直接 new 出来的对象不在容器里，@Autowired 字段永远是 null。
     * 本项目的 JwtAuthenticationFilter 虽然当前无依赖注入需求，
     * 但保持 Bean 声明是最佳实践，避免后续扩展时踩坑。
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter();
    }

    /**
     * 定义 SecurityFilterChain：Spring Security 的核心 Filter 规则链。
     *
     * Spring Security 内部维护一条 Filter 链（FilterChainProxy），
     * 所有 HTTP 请求都会经过这条链上的每个 Filter。
     * 这里通过 HttpSecurity DSL 配置链的行为，最终 build() 返回一个
     * SecurityFilterChain 实例注册到容器，Spring 自动把它接入 FilterChainProxy。
     *
     * 请求经过此链的大致顺序：
     *   [请求进入]
     *     → SecurityContextPersistenceFilter（加载 / 清理 SecurityContext）
     *     → JwtAuthenticationFilter（我们插入的，验签并写入 context）
     *     → UsernamePasswordAuthenticationFilter（表单登录，我们未启用，但占位）
     *     → ExceptionTranslationFilter（捕获鉴权异常，调用 authenticationEntryPoint）
     *     → AuthorizationFilter（检查 authorizeHttpRequests 规则）
     *     → [DispatcherServlet → Controller]
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // ── 1. 关闭 CSRF 保护 ────────────────────────────────────────────────
                // CSRF（跨站请求伪造）攻击利用浏览器自动携带 Cookie 的特性发起伪造请求。
                // 本项目 token 放在 Header 里，浏览器不会自动携带，天然防 CSRF，
                // 无需 Spring Security 额外生成 / 校验 CSRF Token，关掉省去无用开销。
                // 移动端调用也不需要 CSRF，关闭是 JWT 无状态架构的标准做法。
                .csrf(AbstractHttpConfigurer::disable)

                // ── 2. Session 策略：完全无状态 ─────────────────────────────────────
                // STATELESS：Spring Security 不创建也不使用 HttpSession。
                // 每个请求必须通过 Header token 自证身份，服务端零状态存储。
                // 好处：天然支持横向扩展（多实例无需 Session 共享），适合微服务。
                // 如果不设这一项，Spring Security 默认会创建 Session 并存 SecurityContext，
                // 与 JWT 无状态的设计理念冲突，还会产生不必要的内存开销。
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── 3. 路由鉴权规则 ──────────────────────────────────────────────────
                // 规则按声明顺序匹配，第一个匹配的规则生效，后续规则不再检查。
                // 务必把 permitAll 的白名单路径放在 anyRequest 前面。
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/internal/**",       // 内部接口：微服务间直连调用，无 token
                                "/swagger-ui/**",     // Swagger 静态资源目录
                                "/swagger-ui.html",   // Swagger 入口 HTML
                                "/v3/api-docs/**"     // OpenAPI 描述文件
                        ).permitAll()                 // 以上路径无需鉴权，直接放行
                        .anyRequest().authenticated() // 其余所有路径必须已认证（SecurityContext 里有 Authentication）
                )

                // ── 4. 插入 JWT Filter ───────────────────────────────────────────────
                // 把 JwtAuthenticationFilter 放在 UsernamePasswordAuthenticationFilter 之前。
                //
                // 为什么是这个位置？
                //   UsernamePasswordAuthenticationFilter 是 Spring Security 处理表单登录的 Filter，
                //   本项目不用表单登录，但它是 Filter 链中路由鉴权之前的标准"认证阶段"位置。
                //   在它之前插入，确保 JWT Filter 在鉴权规则检查（AuthorizationFilter）之前运行，
                //   让 SecurityContext 里的 Authentication 在鉴权时已经就绪。
                //
                // JwtAuthenticationFilter 内部逻辑：
                //   1. 从 Header "token" 取 JWT 字符串
                //   2. JwtUtil.parse(token) 验签并解析出 userId（纯计算，无 IO）
                //   3. 构造 UsernamePasswordAuthenticationToken(userId, null, emptyList)
                //      写入 SecurityContextHolder，表示"此请求已认证，身份是 userId"
                //   4. token 无效 / 不存在 → 不写 context，继续往下走
                //      → AuthorizationFilter 检查规则 → 白名单放行，受保护路径触发 authenticationEntryPoint
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)

                // ── 5. 自定义 401 响应（AuthenticationEntryPoint）──────────────────
                // 当请求需要认证（anyRequest().authenticated()）但 SecurityContext 为空时，
                // Spring Security 默认返回 302 重定向到登录页，前后端分离架构下这不合适。
                // 自定义 AuthenticationEntryPoint，直接返回 JSON 401，
                // 格式与项目统一错误响应（Result）保持一致，方便前端 Axios 拦截器统一处理。
                //
                // code=40001 对应 ResultCode.NOT_LOGIN，前端识别后跳转登录页。
                //
                //Security 过滤器内部抛出的认证鉴权异常，不会跑到 @RestControllerAdvice 全局异常处理器！因为异常发生在过滤器层，还没到 DispatcherServlet，全局异常处理器是 DispatcherServlet 之后才生效。所以必须在这里配置。
                //
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":40001,\"msg\":\"请先登录\",\"data\":null}");
                }))

                .build();
    }

    /**
     * 声明空实现的 UserDetailsService，屏蔽 Spring Boot 的自动生成密码警告。
     *
     * 问题根源：
     *   引入 spring-boot-starter-security 后，Spring Boot 的
     *   UserDetailsServiceAutoConfiguration 会检测是否存在 UserDetailsService Bean。
     *   如果不存在，它会自动创建一个内存用户（用户名 "user"，密码随机生成），
     *   并在启动日志里打印 "Using generated security password: xxxx-xxxx"。
     *   这个行为在生产环境下是危险的警告信号（说明安全配置可能未完成）。
     *
     * 解决方式：
     *   声明一个自定义 UserDetailsService Bean，Spring Boot 检测到后不再自动创建，
     *   警告消失。实现里直接抛异常，因为本项目完全走 JWT 鉴权，
     *   Spring Security 的 UserDetailsService 体系（表单登录、Basic Auth 等）
     *   根本不会被调用到，这个 Bean 只是为了告诉 Spring Boot "我知道我在做什么"。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("JWT-only mode");
        };
    }
}
