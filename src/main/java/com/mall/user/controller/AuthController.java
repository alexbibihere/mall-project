package com.mall.user.controller;

import com.mall.common.Result;
import com.mall.user.service.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<Long> register(@RequestBody @Validated RegisterReq req) {
        Long userId = userService.register(req.getPhone(), req.getPassword(), req.getSmsCode());
        return Result.ok(userId);
    }

    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody @Validated LoginReq req) {
        String token = userService.login(req.getPhone(), req.getPassword());
        return Result.ok(Map.of("token", token));
    }

    @Data
    public static class RegisterReq {
        @NotBlank
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式错误")
        private String phone;
        @NotBlank
        private String password;
        @NotBlank
        private String smsCode;
    }

    @Data
    public static class LoginReq {
        @NotBlank
        private String phone;
        @NotBlank
        private String password;
    }
}
