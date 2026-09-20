package com.mall.order.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.seata.core.context.RootContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M2.2 Seata AT：全局事务 XID 经 Feign 请求头（TX_XID，Seata 官方约定）向分支方传播。
 * TM(order) 发起的 HTTP 分支调用携带 XID，product 侧据此绑定分支上下文。
 */
@Configuration
public class SeataFeignConfig {

    @Bean
    public RequestInterceptor seataXidRelayInterceptor() {
        return (RequestTemplate template) -> {
            String xid = RootContext.getXID();
            if (xid != null && !xid.isBlank()) {
                template.header(RootContext.KEY_XID, xid);
            }
        };
    }
}
