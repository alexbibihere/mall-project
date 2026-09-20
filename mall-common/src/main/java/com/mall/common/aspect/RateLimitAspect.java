package com.mall.common.aspect;

import com.mall.common.BizException;
import com.mall.common.UserContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 滑动窗口限流切面：ZSET 时间窗计数，Lua 原子执行。
 * key: ratelimit:{userId}:{类.方法}
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> SLIDE_WINDOW = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)
            local cnt = redis.call('ZCARD', key)
            if cnt >= limit then
              return 0
            end
            redis.call('ZADD', key, now, now .. '-' .. math.random())
            redis.call('PEXPIRE', key, window * 1000 + 1000)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    @Before("@annotation(rateLimit)")
    public void check(JoinPoint jp, com.mall.common.annotation.RateLimit rateLimit) {
        Long userId = UserContext.get();
        String method = jp.getSignature().toShortString();
        long now = System.currentTimeMillis();
        Long allowed = redis.execute(SLIDE_WINDOW,
                List.of("ratelimit:" + userId + ":" + method),
                String.valueOf(now), String.valueOf(rateLimit.windowSeconds()),
                String.valueOf(rateLimit.limit()));
        if (allowed == null || allowed == 0) {
            throw new BizException(429, "请求过于频繁，请稍后再试");
        }
    }
}
