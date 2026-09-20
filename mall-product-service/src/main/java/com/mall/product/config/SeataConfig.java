package com.mall.product.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.seata.core.context.RootContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * M2.2 Seata AT 分支接入（mall-product，分支方）：
 * 1. seataXidInterceptor 从请求头（TX_XID）解析全局 XID 并绑定 RootContext；
 *    /internal/** 扣减 SQL 在 XID 上下文中经 DataSourceProxy 自动注册为 AT 分支（写 undo_log）；
 * 2. seataXidForwardInterceptor 预留：product 若再向下游发起 Feign，继续透传 XID。
 */
@Configuration
public class SeataConfig implements WebMvcConfigurer {

    @Bean
    public HandlerInterceptor seataXidInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String xid = request.getHeader(RootContext.KEY_XID);
                if (xid != null && !xid.isBlank()) {
                    RootContext.bind(xid);
                }
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception ex) {
                RootContext.unbind(); // Tomcat 线程复用，必须解绑
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(seataXidInterceptor()).addPathPatterns("/**");
    }

    @Bean
    public RequestInterceptor seataXidForwardInterceptor() {
        return (RequestTemplate template) -> {
            String xid = RootContext.getXID();
            if (xid != null && !xid.isBlank()) {
                template.header(RootContext.KEY_XID, xid);
            }
        };
    }
}
