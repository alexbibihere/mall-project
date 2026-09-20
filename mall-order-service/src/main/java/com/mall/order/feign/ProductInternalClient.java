package com.mall.order.feign;

import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;

/**
 * 商品服务内部接口：扣减/回补/快照字段。
 */
@FeignClient(name = "mall-product", contextId = "productInternalClient")
public interface ProductInternalClient {

    @PostMapping("/internal/stock/{productId}/deduct/{num}")
    Result<Boolean> deduct(@PathVariable("productId") Long productId, @PathVariable("num") int num);

    @PostMapping("/internal/stock/{productId}/restore/{num}")
    Result<Void> restore(@PathVariable("productId") Long productId, @PathVariable("num") int num);

    @GetMapping("/internal/product/{id}")
    Result<Map<String, Object>> product(@PathVariable("id") Long id);
}
