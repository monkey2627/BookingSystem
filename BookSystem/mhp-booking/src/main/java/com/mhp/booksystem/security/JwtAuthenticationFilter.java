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
/*
* ```java
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
 * JWT自定义认证过滤器
 * 前后端分离JWT登录方案：从请求头解析token，解析成功后向SpringSecurity上下文填充认证信息
 * 继承 OncePerRequestFilter：模板抽象类，保证一次原始请求无论发生多少次forward内部转发，本过滤器业务逻辑只执行一次
 * 属于SpringSecurity内部虚拟过滤器链中的自定义过滤器，需要通过 http.addFilterBefore() 加入Security链
 * 执行位置：放在 UsernamePasswordAuthenticationFilter 之前；保证处于 ExceptionHandlingFilter 之后，异常可以被捕获
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * 模板方法：子类实现具体过滤业务逻辑
     * OncePerRequestFilter 的 doFilter() 被final修饰，不可重写；父类控制转发重复执行逻辑，回调本方法
     * @param request  Http请求对象，由Tomcat提供，封装http请求全部信息
     * @param response Http响应对象，用于向浏览器写返回数据
     * @param chain    过滤器链，调用 chain.doFilter(request,response) 放行，执行后续过滤器
     * @throws ServletException Servlet相关异常
     * @throws IOException      IO异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 从请求头中获取token字段，前端将jwt令牌放在header的token键中
        String token = request.getHeader("token");

        // 判断请求携带了token令牌
        if (token != null) {
            try {
                // 调用JWT工具类解析token，从payload中获取用户ID
                // 此处会抛出异常：token过期、签名篡改、格式错误都会抛出异常进入catch块
                Long userId = JwtUtil.parse(token);

                /**
                 * 构造 UsernamePasswordAuthenticationToken 对象
                 * 三参数构造器：代表【已认证完成】的Authentication对象
                 * 参数1 principal：认证主体，这里存入用户ID；后续业务代码可通过SecurityContext获取该用户id
                 * 参数2 credentials：凭证（密码），jwt模式不需要密码，填null
                 * 参数3 authorities：用户权限集合；当前传入空集合，生产环境需要查询数据库获取用户角色/权限
                 *
                 * ⚠️重点：只要使用三参构造，Spring Security就认为该用户已经认证成功，不再需要AuthenticationManager认证
                 */
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());

                /**
                 * 将认证信息存入Security上下文
                 * SecurityContext底层基于ThreadLocal绑定当前请求线程；后续Controller、Service可以直接拿到登录用户信息
                 * 请求处理完毕后Spring会自动清除ThreadLocal上下文，避免线程池复用造成脏数据
                 */
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (Exception ignored) {
                /**
                 * JWT解析失败全部吞掉异常：过期、非法、篡改token都会来到这里
                 * 这里仅仅捕获异常，不做任何响应输出，也不设置Authentication
                 * 上下文保持为空，相当于未登录状态；
                 * 请求继续向后流转到 AuthorizationFilter鉴权过滤器，由鉴权过滤器抛出AuthenticationException，
                 * 上游 ExceptionHandlingFilter捕获异常，调用 authenticationEntryPoint 返回401 JSON给前端
                 *
                 * ⚠️生产建议：打印日志 log.warn("JWT解析失败: {}", e.getMessage());，方便排查问题
                 */
            }
        }

        /**
         * 放行过滤器链，执行后续过滤器
         * 无论有没有token、token是否合法，都执行放行；认证失败不在本filter直接返回401，交给上层Security异常处理器统一处理
         * ⚠️千万不能省略 chain.doFilter()，否则请求链路直接中断，不会到达Controller
         */
        chain.doFilter(request, response);
    }
}
```

        ## 配套补充说明（代码现存问题）
        1. `Collections.emptyList()` 空权限集合：`hasRole()`、`hasAuthority()` 权限校验全部失效；真实项目解析出userId后，需要查询数据库封装用户权限集合传入第三个参数。
        2. catch直接`ignored`吞异常，无日志，线上不方便排查。
        3. Header名称硬编码`token`；行业标准一般使用 `Authorization: Bearer xxx`。
        4. 过滤器注册配置示例（放在SecurityFilterChain配置里面）
        ```java
// 将Jwt过滤器添加到 UsernamePasswordAuthenticationFilter之前，保证在ExceptionHandlingFilter之后
http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

        ## 运行流程回顾
> 请求进来 → ExceptionHandlingFilter → JwtAuthenticationFilter（本类）→ 其他Security过滤器 → AuthorizationFilter鉴权 → Controller
- token有效：SecurityContext设置Authentication，鉴权器认为已认证，放行访问接口
- token为空 / token解析失败：SecurityContext无认证对象，AuthorizationFilter抛出`AuthenticationException`，被`ExceptionHandlingFilter`捕获，执行我们自定义的`authenticationEntryPoint`输出401 JSON。

        如果你需要，我把改进后的生产可用版本也给你。
*
*
*
*
*
*
*
* */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("token");
        if (token != null) {
            try {
                Long userId = JwtUtil.parse(token);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
            } catch (Exception ignored) {
            }
        }
        chain.doFilter(request, response);
    }
}
