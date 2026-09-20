package com.mall.common.sentinel;

import com.mall.common.BizException;
import com.mall.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M2.2 Sentinel 参数级流控（Sentinel 参数流控规则的托管实现）：
 * 保留原 @RateLimit 注解接口（limit/windowSeconds 不变），底层由
 * sentinel_param_flow.lua 在 Redis 上执行滑动窗口（与 Sentinel ParamFlowRule
 * 的「参数单机均摊/滑动窗口」统计口径对齐）。
 * 规则维度：resource=类.方法，paramIndex=0（userId），即「同一用户 1 秒内最多 limit 次」。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ParamFlowRuleManager {

    private static final DefaultRedisScript<Long> PARAM_FLOW = new DefaultRedisScript<>() {{
        setLocation(new ClassPathResource("sentinel_param_flow.lua"));
        setResultType(Long.class);
    }};

    private final StringRedisTemplate redis;

    @Before("@annotation(rateLimit)")
    public void checkParamFlow(JoinPoint jp, com.mall.common.annotation.RateLimit rateLimit) {
        Long userId = UserContext.get();
        String resource = jp.getSignature().toShortString();
        long now = System.currentTimeMillis();
        String key = "flow:" + resource + "__" + userId;
        String member = now + "-" + java.util.UUID.randomUUID();
        Long allowed = redis.execute(PARAM_FLOW,
                List.of(key),
                String.valueOf(now),
                String.valueOf(rateLimit.windowSeconds()),
                String.valueOf(rateLimit.limit()),
                member);
        if (allowed == null || allowed == 0L) {
            log.info("[ParamFlowRuleManager] blocked for param={} resource={}", userId, resource);
            throw new BizException(429, "请求过于频繁，请稍后再试");
        }
    }
}
