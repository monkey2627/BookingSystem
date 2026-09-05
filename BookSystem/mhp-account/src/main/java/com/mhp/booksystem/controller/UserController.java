package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.TokenRefreshDTO;
import com.mhp.booksystem.dto.UserLoginDTO;
import com.mhp.booksystem.dto.UserRegisterDTO;
import com.mhp.booksystem.security.SecurityUtil;
import com.mhp.booksystem.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.ok(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody UserLoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    @PostMapping("/refresh")
    public Result<?> refresh(@Valid @RequestBody TokenRefreshDTO dto) {
        return Result.ok(userService.refreshToken(dto.getRefreshToken()));
    }

    @PostMapping("/logout")
    public Result<?> logout() {
        userService.logout(SecurityUtil.getCurrentUserId());
        return Result.ok();
    }
}
