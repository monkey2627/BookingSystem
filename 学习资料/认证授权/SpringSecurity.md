# 认证与授权
## 认证 authentication
即用户登录，确认系统将某人为用户的这个过程
### 常见认证方式
#### http basic
#### session 认证
* Spring Security默认用这种，但我们后续会用JWT改成token的方式
#### token认证
* 服务端不需要存储用户的登陆记录，只需要存一个token
* ![img.png](img.png)
#### OAuth2认证
* 第三方认证的方式，微信扫码认证等都是通过这个实现的
## 授权 authorization
不同用户所具有的权限不同，各种资源都需要访问权限，如果没有权限，则不能访问对应的资源
# Spring Security
## 如何实现用户没有登陆就跳转到内置登陆页面？
### 思路1-SS使用的
使用过滤器filter如果没有登入就重定向到登录页面
### 思路2
使用拦截器访问具体的控制单元
### 为什么要在过滤器做而不是拦截器
`Filter → DispatcherServlet → Interceptor → Controller`
拦截器**是进入 DispatcherServlet 之后才执行**。
❌ 缺陷：
1. 静态资源、错误页面、forward 转发请求，拦截器不一定生效；Filter 可以拦截全部 web 请求。
2. Spring Security 本身跑在 Filter 层；如果你自己再加拦截器做登录校验：
   Security 已经完成一套鉴权，拦截器又做一套，两套体系，容易冲突。
3. 拦截器只能拿到`HttpServletRequest`
## 基本运行原理
* 本质上Spring Security就是一个有序的过滤器链，请求自上而下经过所有过滤器，所有的操作都是基于这些过滤器
- doFilterInternal()
- - List<Filter> filters = getFilters(firewallRequest) 拿到所有过滤器
## 过滤器详解
### UserNamePasswordAuthenticationFilter类
* 负责处理在登陆页面填写了用户密码后的登录请求的过滤器
* 专门处理**表单登录 POST 请求**,只会拦截login路径的POST请求，不会拦截GET
  @Override
  public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
  throws AuthenticationException {
  // 1. 从request表单提取用户名，默认表单字段名：username
  String username = obtainUsername(request);
  // 2. 从request表单提取密码，默认表单字段名：password
  String password = obtainPassword(request);

  username = (username != null) ? username.trim() : "";
  password = (password != null) ? password : "";

  // 3. 封装认证令牌：UsernamePasswordAuthenticationToken
  // 第一个构造：传入账号密码，此时还未认证，标记为未认证状态,authRequest.isAuthenticated() → false`，此时只是封装请求参数，没有权限。
  UsernamePasswordAuthenticationToken authRequest =
  new UsernamePasswordAuthenticationToken(username, password);

  // 把request的details信息（remoteIp、sessionId等）塞到token，记录请求细节
  authRequest.setDetails(this.authenticationDetailsSource.buildDetails(request));

  // 4. 交给 AuthenticationManager 做真正的认证校验（内部调用UserDetailsService拿用户、比对密码）
  return this.getAuthenticationManager().authenticate(authRequest);
  }
#### attemptAuthentication 执行之后的流程
1. 如果认证**成功**：回到过滤器，执行 `successfulAuthentication()`
    - 把完整 Authentication 存入`SecurityContext`
    - 调用 Session 策略存入 HttpSession
    - 调用成功处理器，重定向 / 返回响应
2. 如果认证**失败**：抛出`AuthenticationException`，执行`unsuccessfulAuthentication()`
    - 交给`ExceptionTranslationFilter`处理，跳转登录页、携带错误信息
### DefaultLogoutPageGenerateingFilter

### DefaultLoginPageGeneratingFilter


## authentication流程核心接口
### AuthenticationManager
**ProviderManager 就是 AuthenticationManager 的唯一默认实现类**，整个 SpringSecurity 容器中提前已经建好这个 Bean，
UserNamePasswordAuthenticationFilter直接拿到容器里这个实例调用其authenticate方法，这个方法是整个认证流程的入口
```
@Override
public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    // 遍历内部保存的所有AuthenticationProvider
    for (AuthenticationProvider provider : this.providers) {
        // 判断这个provider是否支持处理当前token类型（UsernamePasswordAuthenticationToken）
        if (!provider.supports(authentication.getClass())) {
            continue;
        }
        // 交给provider执行认证，常用 DaoAuthenticationProvider
        Authentication result = provider.authenticate(authentication);
        if(result != null){
            // 认证成功，返回已认证Authentication对象
            return result;
        }
    }
    // 所有provider都处理失败，抛出异常
    throw new ProviderNotFoundException(...);
}
```

- `providers` 集合里面默认就放了 **DaoAuthenticationProvider**。
- `DaoAuthenticationProvider` 的 `authenticate()` 方法内部，才会调用 `userDetailsService.loadUserByUsername(username)` 查询用户，再做密码比对。

> 调用链路：
> 过滤器拿到**容器已存在的ProviderManager实例** → `.authenticate(未认证token)`
> → 循环遍历内部list里的各个`AuthenticationProvider`
> → 匹配到DaoAuthenticationProvider，执行它的authenticate
### UserDetailsService接口
* 唯一的一个方法 loadUserByUsername(String username)，返回值是一个UserDetails对象
* 当什么也没有配置的时候，`UserDetailsServiceAutoConfiguration自动配置类`自动装配出来一个**内存版本的 InMemoryUserDetailsManager**。存在内存中，所以登录的默认行为是将拿到的账号密码和ss自动保存的账号密码比对
* 但实际开发项目中账号密码来自于数据库，因此必须自定义逻辑来替换掉这个默认的逻辑，而自定义逻辑只需要实现UserDetailsService接口，重写里面的
* loadUserByUserName方法即可
### `UserDetailsManager` 继承自 `UserDetailsService`
**在父接口 `loadUserByUsername()` 查询的基础上，额外增加用户 CRUD 能力：创建用户、修改密码、删除用户**。
// 创建用户
void createUser(UserDetails user);
// 更新用户
void updateUser(UserDetails user);
// 删除用户
void deleteUser(String username);
// 修改密码
void changePassword(String oldPassword, String newPassword);
### UserDetails接口 & User 实现类
#### 1. UserDetails 接口
`org.springframework.security.core.userdetails.UserDetails`
> 作用：**标准化定义安全框架需要的用户信息契约**。
SpringSecurity做认证、鉴权时，需要的用户信息全部定义在这个接口。
不管你的数据库实体是什么，最终都要转换成`UserDetails`给Security框架使用。
接口全部抽象方法：
```java
public interface UserDetails {
    // 获取用户名
    String getUsername();
    // 获取密码（加密后的密码）
    String getPassword();
    // 获取该用户拥有的权限/角色集合
    Collection<? extends GrantedAuthority> getAuthorities();
    // =========账号状态4个开关，账号是否可用=========
    // 账号是否没有过期
    boolean isAccountNonExpired();
    // 账号是否没有锁定
    boolean isAccountNonLocked();
    // 凭证(密码)是否没有过期
    boolean isCredentialsNonExpired();
    // 账号是否启用（true正常可用；false账号禁用无法登录）
    boolean isEnabled();
}
```
> 关键点：
1. 这个接口**不对应数据库表**，只是契约；你的数据库实体类不需要实现这个接口。
2. `DaoAuthenticationProvider`拿到`UserDetails`之后，会校验上面4个状态，如果任意一个返回false，直接抛出异常，登录失败。
---
#### 2. User 实现类
全类名：`org.springframework.security.core.userdetails.User`
> 是SpringSecurity**官方提供的UserDetails的默认实现类**。
是一个POJO，记录用户名、密码、权限、四个账号状态。
#### 3. 在 UserDetailsService 里面怎么用
```java
@Service
public class MyUserDetailsService implements UserDetailsService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 1.查询自己数据库实体 SysUser（业务实体）
        SysUser dbUser = sysUserMapper.selectByUsername(username);
        if(dbUser == null){
            throw new UsernameNotFoundException("用户不存在");
        }

        // 2.把数据库查询出来的业务实体，转为Security的UserDetails（User对象）返回
        return User.withUsername(dbUser.getUsername())
                .password(dbUser.getPassword()) //数据库存bcrypt加密后的密码，注意：`User`对象里面存的密码**必须是加密后的密文**，不能存明文
                .authorities("sys:user:list")
                .disabled(!dbUser.getStatus()) //status=0代表账号禁用
                .build();
    }
}
```
#### 4. 常见问题
##### Q1：我能不能直接用数据库实体 SysUser 当做UserDetails？
可以，让`SysUser implements UserDetails`，重写全部7个方法。
> 优缺点：
- 优点：少一次对象转换；
- 缺点：业务实体和Security框架强耦合；后续如果Security版本升级，接口改动会影响业务实体。
> **企业更推荐：业务实体SysUser 和 Security UserDetails分开，做转换，解耦。**

##### Q2：UserDetails 存的密码，登录成功之后去哪里了？
1. `DaoAuthenticationProvider`完成密码比对之后，**会把credentials（密码）清空**。
2. 认证成功构建出来的已认证`UsernamePasswordAuthenticationToken`：
   `principal`存UserDetails对象，**credentials=null**。
> 所以SecurityContext里面不会保存用户密码，防止泄露。
##### Q3：getAuthorities() 返回的 GrantedAuthority
- `SimpleGrantedAuthority`是常用实现；
- `.roles("ADMIN")`等价于`.authorities("ROLE_ADMIN")`；角色会自动拼接`ROLE_`前缀。
- `@PreAuthorize("hasRole('ADMIN')")`底层就是去拿这个集合做匹配。

##### Q4：四个布尔状态校验时机
在`DaoAuthenticationProvider#additionalAuthenticationChecks()`密码比对完成之后，**紧接着校验这4个状态**。
- `isEnabled() = false` → `DisabledException`
- `isAccountNonLocked() = false` → `LockedException`
  抛出异常，登录直接失败。
#### 链路回顾串联
`UsernamePasswordAuthenticationFilter` → ProviderManager → DaoAuthenticationProvider
👉调用`loadUserByUsername()`拿到**UserDetails对象**
👉比对密码，校验账号四个状态
👉校验通过，组装已认证Authentication，principal = UserDetails。

## 自定义登录流程：实现从数据库中取用户和密码验证
1. 实现UserDetailsService接口，重写其中的loadUserByUsername方法，进行用户名校验
> 只要我们实现了这个接口，就会用我们的类去替换默认的那个类
> 这个类只做用户名校验，即只看用户名是否存在，最后的返回值是一个userDetais类，里面至少需要有姓名密码和权限三种信息
2. SS 要求密码必须密文存储，Spring容器中必须有一个PasswordEncoder接口实现类对象
> 常用MD5算法加密或者BCrypt，后者常用，前者安全性已经被破解，SS已经提供了对应的接口实现类，但我们需要将其注入进容器中。
> 我们可以直接创建一个有@Configuration的配置类，在其中创建并且注入bean，对于SS都
> 所有bean都可以这样，直接简单返回即可
> Bcrypt会将明文密码和salt进行混合，并经过多轮哈希计算，其计算量很大能够有效防止暴力破解。
![img_1.png](BCrypt原理.png)
3. loadUserByUsername通过了之后，也就是数据库中确实有这一个用户名，会调用check(UserDetails user)方法，来检查
当前用户的状态，状态检查通过了才往下走
4. 前面封装的authentication对象和userDetails对象，获取其中的明文密码(前)和加密密码(后)，结合我们的加密器开始校验密码是否正确
# SecurityFilterChain
* SS 实现各种功能的地方
* 一旦我们自己配置了，即自己创建了一个bean，则默认的过滤器链DefaultSecurityFilterChian就会失效