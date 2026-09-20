package com.mall.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器：解析 JWT 写入 UserContext。
 * 白名单路径在 WebConfig 中注册时排除。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        Long userId = token == null ? null : SpringContextHolder.getBean(JwtUtil.class).parseUserId(token);
        if (userId == null) {
            throw BizException.of("未登录或登录已过期");
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear(); // 线程池复用，必须清理 ThreadLocal
    }
}
