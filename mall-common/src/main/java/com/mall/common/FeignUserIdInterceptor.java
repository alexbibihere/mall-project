package com.mall.common;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 调用透传 X-User-Id（服务间调用保持用户上下文）。
 */
@Component
public class FeignUserIdInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                template.header("X-User-Id", userId);
            }
        }
    }
}
