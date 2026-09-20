package com.mall.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器（微服务版）：
 * JWT 校验统一在网关完成，网关注入 X-User-Id 头；本拦截器只做「头存在性」校验与上下文注入。
 * 服务间 Feign 调用由 FeignUserIdInterceptor 透传该头。
 * 白名单：网关层负责外部白名单；服务层仅放行渠道回调等无用户上下文路径。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 无用户上下文也放行的路径（浏览/注册登录类公开接口 + 支付渠道回调 + 服务间内部接口）。 */
    private static final String[] OPEN_PREFIXES = {
            "/api/auth",                 // 注册/登录
            "/api/products",             // 商品浏览（网关白名单 + 服务层双放行）
            "/api/payments/mock/notify/",// 支付渠道回调
            "/internal/"                 // 服务间 Feign 接口（网关不路由该前缀，外网不可达）
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        for (String prefix : OPEN_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            throw BizException.of(401, "未登录或登录已过期");
        }
        UserContext.set(Long.valueOf(userId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear(); // 线程池复用，必须清理 ThreadLocal
    }
}
