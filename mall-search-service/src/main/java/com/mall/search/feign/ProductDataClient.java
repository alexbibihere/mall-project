package com.mall.search.feign;

import com.mall.common.mq.ProductChangedMessage;
import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 全量对账：从 product 拉 DB 全量快照（内部接口）。
 */
@FeignClient(name = "mall-product", contextId = "searchProductInternalClient")
public interface ProductDataClient {

    @GetMapping("/internal/product/all")
    Result<List<ProductChangedMessage>> all();
}
