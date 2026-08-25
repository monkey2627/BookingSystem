package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.SecureUtil;
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
        // MD5 不可逆加密，只存摘要，忘记密码只能重置不能找回
        user.setPassword(SecureUtil.md5(dto.getPassword()));
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

        if (!user.getPassword().equals(SecureUtil.md5(dto.getPassword()))) {
            throw new BusinessException("密码错误");
        }

        return buildLoginVO(user);
    }

    /**
     * 登录/注册成功后统一构建返回值。
     *
     * StpUtil.login(id) 会在 Redis 里写入 token→userId 映射（Sa-Token 托管），
     * getTokenValue() 拿到刚生成的 token 字符串返回给前端。
     * 前端把 token 存在 localStorage，后续请求通过 Axios 拦截器放入 Header。
     *
     * hasMerchantProfile 告诉前端当前用户是否有商家资料，
     * 前端据此决定是否展示"档期管理"等商家专属菜单，避免额外一次接口查询。
     */
    private UserLoginVO buildLoginVO(User user) {
        // ── StpUtil.login(id) 做了两件事 ─────────────────────────────────────
        // 1. 在 Redis 里写入一条记录：token（自动生成的随机字符串）→ userId
        //    后续每个请求带着这个 token 过来，Sa-Token 去 Redis 查对应的 userId，
        //    查到了就说明已登录，查不到（token 过期或不存在）就是未登录。
        //    这就是 token 鉴权的本质：Redis 是"有效 token 的名单"。
        //
        // 2. 把刚生成的 token 绑定到当前请求的线程（ThreadLocal）。
        //    ThreadLocal 是 Java 的线程隔离机制：每个线程有自己独立的变量副本，
        //    线程 A 写入的值线程 B 看不到。Tomcat 每个请求用一个线程处理，
        //    login() 把 token 写入当前线程的 ThreadLocal，
        //    后续同一个请求里调用 getTokenValue() 就能直接从 ThreadLocal 取出，
        //    不需要再传参，也不会拿到别的请求的 token。
        StpUtil.login(user.getId());

        UserLoginVO.UserInfoVO userInfoVO = new UserLoginVO.UserInfoVO();
        userInfoVO.setId(user.getId());
        userInfoVO.setNickname(user.getNickname());
        userInfoVO.setAvatar(user.getAvatar());
        // 不返回"是否商家"字段：前端采用闲鱼模式，所有用户都能访问买/卖功能，
        // 进入"我卖出的"页面若没有商家资料则展示空列表，无需在登录时判断身份。
        // 删除此字段同时省去一次登录时的额外 DB 查询。

        UserLoginVO vo = new UserLoginVO();
        // getTokenValue() 从当前线程的 ThreadLocal 取出 login() 绑定的 token 字符串
        vo.setToken(StpUtil.getTokenValue());
        vo.setUserInfo(userInfoVO);
        return vo;
    }
}
