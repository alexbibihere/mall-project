package com.mall.seckill.feign;

import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 商品服务 Feign 客户端（秒杀校验商品存在性）。
 */
@FeignClient(name = "mall-product", contextId = "seckillProductClient")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    Result<Map<String, Object>> detail(@PathVariable("id") Long id);
}
