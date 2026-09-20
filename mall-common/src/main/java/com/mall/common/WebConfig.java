package com.mall.common;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 配置：注册 X-User-Id 拦截器（微服务版，白名单收敛到网关）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 服务层豁免清单（属性驱动）：如 search 免登录可搜，见各服务 yml 的 mall.auth.exclude-patterns */
    @Value("${mall.auth.exclude-patterns:}")
    private List<String> excludePatterns;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(excludePatterns.isEmpty() ? List.of("/error") : excludePatterns);
    }
}
