package com.mhp.booksystem.service.impl;

import com.mhp.booksystem.security.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.UserLoginDTO;
import com.mhp.booksystem.dto.UserRegisterDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.service.UserService;
import com.mhp.booksystem.vo.UserLoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final MerchantMapper merchantMapper;

    // BCryptPasswordEncoder 是线程安全的无状态对象，直接作为字段持有即可
    // 不用 @Autowired 注入，避免为此单独声明一个 @Bean
    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ── @Override 说明 ────────────────────────────────────────────────────────
    // @Override 的真正含义是"请编译器确认这个方法存在于父类或接口中，否则报错"。
    // 它不只用于继承（extends），实现接口（implements）的方法同样应该加：
    //   - 方法名拼错（registre 而非 register）→ 编译直接报错，不会等到运行时
    //   - 接口方法签名变更后忘记同步实现    → 编译直接报错
    // 没有 @Override 时以上错误在编译期不会被发现，只会在运行时才暴露。
    // 结论：实现接口的方法都应该加 @Override，是防御性编程的好习惯。
    @Override
    public UserLoginVO register(UserRegisterDTO dto) {
        // 手机号唯一性校验，先查后插，并发概率极低（无需加锁）
        //
        // lambdaQuery() 来自父类 ServiceImpl，返回一个针对 User 表的 Lambda 条件构造器。
        // 链式调用最终会翻译成 SQL 发给数据库，等价于：
        //   SELECT COUNT(*) FROM user WHERE phone = ?
        //
        // .eq(User::getPhone, dto.getPhone())
        //   eq = equal，添加一个 WHERE 等值条件。
        //   第一个参数 User::getPhone 是方法引用，用来表示字段名：
        //     - MyBatis-Plus 内部通过反射把 getPhone 解析为列名 "phone"
        //     - 相比直接写字符串 .eq("phone", ...)，方法引用写法在字段名拼错时
        //       编译直接报错，IDE 有提示，重构时也会自动跟着改
        //   第二个参数 dto.getPhone() 是条件的值，对应 SQL 里的 ?
        //
        // .exists()
        //   触发查询，执行 SELECT COUNT(*) > 0，返回 boolean：
        //     true  → 表里已有这个手机号
        //     false → 手机号未注册
        boolean exists = lambdaQuery()
                .eq(User::getPhone, dto.getPhone())
                .exists();
        if (exists) {
            throw new BusinessException(ResultCode.USER_PHONE_EXISTS);
        }

        User user = new User();
        user.setPhone(dto.getPhone());
        // BCrypt 内置随机盐，每次 encode 结果不同，相同明文不产生相同哈希，彩虹表无效
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        save(user);

        // 注册即登录，省去用户再次输密码
        return buildLoginVO(user);
    }

    @Override
    public UserLoginVO login(UserLoginDTO dto) {
        User user = lambdaQuery()
                .eq(User::getPhone, dto.getPhone())
                .one();
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // matches(明文, 哈希) 内部自动提取盐值后比较，不能用 equals
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("密码错误");
        }

        return buildLoginVO(user);
    }

    /**
     * 登录/注册成功后统一构建返回值。
     *
     * JwtUtil.generate(userId) 生成自包含的 JWT：Header.Payload.Signature 三段式字符串。
     * Payload 里包含 userId（sub 字段）和过期时间（exp 字段），不需要查 Redis 就能验证。
     * 前端把 token 存在 localStorage，后续请求通过 Axios 拦截器放入 Header "token"。
     * 后端 JwtAuthenticationFilter 读取该 Header，解析出 userId 写入 SecurityContext，
     * Service 层通过 SecurityUtil.getCurrentUserId() 取出，无 Redis IO，O(1)。
     */
    private UserLoginVO buildLoginVO(User user) {
        UserLoginVO.UserInfoVO userInfoVO = new UserLoginVO.UserInfoVO();
        userInfoVO.setId(user.getId());
        userInfoVO.setNickname(user.getNickname());
        userInfoVO.setAvatar(user.getAvatar());

        UserLoginVO vo = new UserLoginVO();
        vo.setToken(JwtUtil.generate(user.getId()));
        vo.setUserInfo(userInfoVO);
        return vo;
    }
}
