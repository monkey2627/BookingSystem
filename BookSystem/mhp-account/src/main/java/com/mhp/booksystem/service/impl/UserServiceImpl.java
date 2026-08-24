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

    @Override
    public UserLoginVO register(UserRegisterDTO dto) {
        // 手机号唯一性校验，先查后插，并发概率极低（无需加锁）
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
        StpUtil.login(user.getId());

        boolean hasMerchantProfile = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, user.getId())) != null;

        UserLoginVO.UserInfoVO userInfoVO = new UserLoginVO.UserInfoVO();
        userInfoVO.setId(user.getId());
        userInfoVO.setNickname(user.getNickname());
        userInfoVO.setAvatar(user.getAvatar());
        userInfoVO.setHasMerchantProfile(hasMerchantProfile);

        UserLoginVO vo = new UserLoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setUserInfo(userInfoVO);
        return vo;
    }
}
