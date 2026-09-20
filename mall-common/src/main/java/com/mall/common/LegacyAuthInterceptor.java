package com.mall.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 旧 JWT 版拦截器：JWT 校验职责已上移网关（见 AuthInterceptor X-User-Id 方案）。
 * 保留 JwtUtil 供网关与用户服务签发/验签使用。
 */
@Component
public class LegacyAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        return true; // 兼容占位：不再注册
    }
}
