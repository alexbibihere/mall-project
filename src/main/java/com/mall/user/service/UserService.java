package com.mall.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.BizException;
import com.mall.common.JwtUtil;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    /** 注册：手机号唯一；验证码 Redis 校验（mock 固定 123456）。 */
    public Long register(String phone, String password, String smsCode) {
        if (!"123456".equals(smsCode)) {
            throw BizException.of("验证码错误");
        }
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (exists > 0) {
            throw BizException.of("手机号已注册");
        }
        User u = new User();
        u.setPhone(phone);
        u.setPasswordHash(sha256(password));
        u.setNickName("用户" + phone.substring(phone.length() - 4));
        userMapper.insert(u);
        return u.getId();
    }

    /** 登录：签发 JWT；连续 5 次失败锁 30 分钟（Redis 计数）。 */
    public String login(String phone, String password) {
        String lockKey = "login:fail:" + phone;
        String fails = redis.opsForValue().get(lockKey);
        if (fails != null && Integer.parseInt(fails) >= 5) {
            throw BizException.of(429, "失败次数过多，请 30 分钟后再试");
        }
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (u == null || !u.getPasswordHash().equals(sha256(password))) {
            Long n = redis.opsForValue().increment(lockKey);
            redis.expire(lockKey, Duration.ofMinutes(30));
            throw BizException.of("手机号或密码错误(第" + n + "次)");
        }
        redis.delete(lockKey);
        return jwtUtil.issue(u.getId());
    }

    private String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
