package com.mall.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等（一次性令牌）：客户端先取 token，请求携带 X-Idem-Token 头。
 * setnx 原子占位，重复请求直接 409。一次有效（防重放）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
