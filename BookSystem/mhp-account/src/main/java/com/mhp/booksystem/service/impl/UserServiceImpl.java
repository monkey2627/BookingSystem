package com.mhp.booksystem.service.impl;

import com.mhp.booksystem.security.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.UserLoginDTO;
import com.mhp.booksystem.dto.UserRegisterDTO;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.service.UserService;
import com.mhp.booksystem.vo.UserLoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final MerchantMapper merchantMapper;
    private final StringRedisTemplate stringRedisTemplate;

    // BCryptPasswordEncoder 是线程安全的无状态对象，直接作为字段持有即可
    // 不用 @Autowired 注入，避免为此单独声明一个 @Bean
    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final String RT_PREFIX = "rt:";
    private static final long RT_TTL_DAYS = 7;

    @Override
    public UserLoginVO register(UserRegisterDTO dto) {
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
     * 换发新 accessToken。
     *
     * refreshToken 格式：{userId}:{UUID32}，存于 Redis key rt:{userId}，TTL 7天。
     * 验证流程：分割出 userId → 查 Redis 比对完整值 → 通过则签发新 accessToken。
     * refreshToken 本身不变（不旋转），TTL 不续期，自然衰减至 7天后失效。
     * 封号/改密码：del rt:{userId} 即可，下次 refresh 失败，旧 accessToken 最多再用 2小时。
     */
    @Override
    public UserLoginVO refreshToken(String refreshToken) {
        String[] parts = refreshToken.split(":", 2);
        if (parts.length != 2) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId;
        try {
            userId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        String stored = stringRedisTemplate.opsForValue().get(RT_PREFIX + userId);
        if (!refreshToken.equals(stored)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 只换发 accessToken，refreshToken 及其 TTL 不变
        String newAccessToken = JwtUtil.generate(userId);

        UserLoginVO.UserInfoVO userInfoVO = new UserLoginVO.UserInfoVO();
        userInfoVO.setId(user.getId());
        userInfoVO.setNickname(user.getNickname());
        userInfoVO.setAvatar(user.getAvatar());

        UserLoginVO vo = new UserLoginVO();
        vo.setAccessToken(newAccessToken);
        vo.setRefreshToken(refreshToken);
        vo.setUserInfo(userInfoVO);
        return vo;
    }

    @Override
    public void logout(Long userId) {
        stringRedisTemplate.delete(RT_PREFIX + userId);
    }

    /**
     * 登录/注册成功后统一构建双令牌返回值。
     *
     * accessToken：JWT（2小时），Header "token" 携带，JwtAuthenticationFilter 验签，无 Redis IO。
     * refreshToken：{userId}:{UUID}，存 Redis rt:{userId}，TTL 7天；
     *   accessToken 过期时前端凭此换新 accessToken，封号时删 Redis key 即可吊销。
     */
    private UserLoginVO buildLoginVO(User user) {
        String accessToken = JwtUtil.generate(user.getId());
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String refreshToken = user.getId() + ":" + uuid;

        stringRedisTemplate.opsForValue().set(
                RT_PREFIX + user.getId(), refreshToken, RT_TTL_DAYS, TimeUnit.DAYS);

        UserLoginVO.UserInfoVO userInfoVO = new UserLoginVO.UserInfoVO();
        userInfoVO.setId(user.getId());
        userInfoVO.setNickname(user.getNickname());
        userInfoVO.setAvatar(user.getAvatar());

        UserLoginVO vo = new UserLoginVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setUserInfo(userInfoVO);
        return vo;
    }
}
