package com.mall.common.aspect;

import com.mall.common.BizException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 幂等切面：X-Idem-Token 一次性占位（setnx），重复请求 409。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String KEY_PREFIX = "idem:token:";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final String HEADER = "X-Idem-Token";

    private final StringRedisTemplate redis;

    @Before("@annotation(com.mall.common.annotation.Idempotent)")
    public void check(JoinPoint jp) {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw BizException.of("非 Web 请求上下文");
        }
        String token = attrs.getRequest().getHeader(HEADER);
        if (token == null || token.isBlank()) {
            throw BizException.of("缺少幂等令牌，请先获取 " + HEADER);
        }
        Boolean ok = redis.opsForValue().setIfAbsent(KEY_PREFIX + token, "1", TOKEN_TTL);
        if (!Boolean.TRUE.equals(ok)) {
            throw new BizException(409, "重复请求（令牌已使用）");
        }
    }
}
