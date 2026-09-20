package com.mall.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流（Redis+Lua 滑动窗口，用户维度）。
 * 对标 Sentinel QPS 限流的同源思路；M2 微服务化后可平滑替换为 Sentinel 注解。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 时间窗口内最大请求数 */
    int limit() default 10;

    /** 窗口长度（秒） */
    int windowSeconds() default 1;
}
