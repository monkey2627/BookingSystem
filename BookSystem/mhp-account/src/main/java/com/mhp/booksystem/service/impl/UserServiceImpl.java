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
        boolean exists = lambdaQuery()
                .eq(User::getPhone, dto.getPhone())
                .exists();
        if (exists) {
            throw new BusinessException(ResultCode.USER_PHONE_EXISTS);
        }

        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPassword(SecureUtil.md5(dto.getPassword()));
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

        if (!user.getPassword().equals(SecureUtil.md5(dto.getPassword()))) {
            throw new BusinessException("密码错误");
        }

        return buildLoginVO(user);
    }

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
