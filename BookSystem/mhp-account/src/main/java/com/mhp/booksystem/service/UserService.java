package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.UserLoginDTO;
import com.mhp.booksystem.dto.UserRegisterDTO;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.vo.UserLoginVO;

public interface UserService extends IService<User> {

    UserLoginVO register(UserRegisterDTO dto);

    UserLoginVO login(UserLoginDTO dto);

    UserLoginVO refreshToken(String refreshToken);

    void logout(Long userId);
}
