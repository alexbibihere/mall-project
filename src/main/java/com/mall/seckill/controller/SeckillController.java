package com.mall.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.Result;
import com.mall.common.annotation.Idempotent;
import com.mall.common.annotation.RateLimit;
import com.mall.order.entity.Order;
import com.mall.order.mapper.OrderMapper;
import com.mall.seckill.service.SeckillService;
import com.mall.seckill.service.SeckillStockService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀接口（M1.5）。
 * 限流 5次/秒/用户；幂等令牌一次性（先 GET token 再携带提交）。
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
@Validated
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillStockService stockService;
    private final OrderMapper orderMapper;

    /** 活动预热：按给定总库存建分桶（演示接口，真实活动由运营后台触发）。 */
    @PostMapping("/products/{productId}/warmup/{stock}")
    public Result<String> warmup(@PathVariable Long productId, @PathVariable int stock) {
        stockService.warmup(productId, stock);
        return Result.ok("预热完成: " + stock);
    }

    /** 幂等令牌发放。 */
    @GetMapping("/token")
    public Result<String> token() {
        return Result.ok(UUID.randomUUID().toString().replace("-", ""));
    }

    /** 秒杀下单：限流+幂等切面 -> Redis 分桶预扣 -> MQ 异步落库。 */
    @RateLimit(limit = 5, windowSeconds = 1)
    @Idempotent
    @PostMapping("/orders")
    public Result<Map<String, Object>> seckill(@RequestBody @Validated SeckillReq req) {
        String orderNo = seckillService.seckill(req.getProductId(), req.getQuantity(), req.getAddressId());
        return Result.ok(Map.of("orderNo", orderNo, "status", "ACCEPTED"));
    }

    /** 受理结果轮询（异步落库有短暂延迟，ACCEPTED -> UNPAID/不存在）；仅本人可查。 */
    @GetMapping("/orders/{orderNo}")
    public Result<Map<String, Object>> result(@PathVariable String orderNo) {
        Long userId = com.mall.common.UserContext.get();
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        Map<String, Object> view = new HashMap<>();
        view.put("orderNo", orderNo);
        if (order == null) {
            view.put("status", "PROCESSING"); // 还在 MQ 排队/消费中
        } else {
            if (!order.getUserId().equals(userId)) {
                throw com.mall.common.BizException.of("订单不存在"); // 越权防护
            }
            view.put("status", order.getStatus());
            view.put("payAmount", order.getPayAmount());
        }
        return Result.ok(view);
    }

    /** 分桶合计余量（对账演示）。 */
    @GetMapping("/stock/{productId}")
    public Result<Long> remain(@PathVariable Long productId) {
        return Result.ok(stockService.remainTotal(productId));
    }

    @Data
    public static class SeckillReq {
        @NotNull
        private Long productId;
        @NotNull
        private Long addressId;
        @Min(1)
        @Max(5)
        private int quantity;
    }
}
