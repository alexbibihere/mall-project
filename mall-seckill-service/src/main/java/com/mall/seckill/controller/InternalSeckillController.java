package com.mall.seckill.controller;

import com.mall.common.Result;
import com.mall.seckill.service.SeckillStockService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 服务间内部接口：秒杀分桶回补（订单消费失败时调用）。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalSeckillController {

    private final SeckillStockService stockService;

    @PostMapping("/seckill/restore")
    public Result<Void> restore(@RequestBody RestoreReq req) {
        stockService.restore(req.getProductId(), req.getUserId(), req.getBucketIdx(), req.getQuantity());
        return Result.ok();
    }

    @Data
    public static class RestoreReq {
        private Long productId;
        private Long userId;
        private int bucketIdx;
        private int quantity;
    }
}
