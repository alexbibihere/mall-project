package com.mall.cart.feign;

import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 商品服务 Feign 客户端（购物车补实时价格）。
 */
@FeignClient(name = "mall-product", contextId = "productClient")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    Result<Map<String, Object>> detail(@PathVariable("id") Long id);
}
