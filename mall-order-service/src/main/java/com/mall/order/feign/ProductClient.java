package com.mall.order.feign;

import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 商品服务 Feign 客户端（订单取商品名/价格做快照）。
 */
@FeignClient(name = "mall-product", contextId = "orderProductClient")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    Result<Map<String, Object>> detail(@PathVariable("id") Long id);
}
