package com.mall.product.controller;

import com.mall.common.Result;
import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务间内部接口：库存扣减/回补/商品查询（仅限内网服务调用，网关不路由 /internal/**）。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalStockController {

    private final StockService stockService;
    private final ProductMapper productMapper;

    /** 静默扣减（乐观锁）：true=成功 false=库存不足。 */
    @PostMapping("/stock/{productId}/deduct/{num}")
    public Result<Boolean> deduct(@PathVariable Long productId, @PathVariable int num) {
        return Result.ok(stockService.deductQuiet(productId, num));
    }

    /** 回补。 */
    @PostMapping("/stock/{productId}/restore/{num}")
    public Result<Void> restore(@PathVariable Long productId, @PathVariable int num) {
        stockService.restore(productId, num);
        return Result.ok();
    }

    /** 商品字段（供订单快照）。 */
    @GetMapping("/product/{id}")
    public Result<Map<String, Object>> product(@PathVariable Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            return Result.error(404, "商品不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("price", p.getPrice());
        m.put("stock", p.getStock());
        return Result.ok(m);
    }
}
