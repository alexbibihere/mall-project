package com.mall.seckill.controller;

import com.mall.common.BizException;
import com.mall.common.Result;
import com.mall.common.annotation.Idempotent;
import com.mall.common.annotation.RateLimit;
import com.mall.common.mq.MqTopics;
import com.mall.common.mq.SeckillMessage;
import com.mall.common.SnowflakeIdGenerator;
import com.mall.common.UserContext;
import com.mall.seckill.feign.ProductClient;
import com.mall.seckill.service.SeckillStockService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀受理服务（M2）：限流/幂等切面 -> Redis 分桶预扣 -> MQ（mall-order 消费落库）。
 * 订单查询/轮询已归属 mall-order（/api/orders），本服务只管受理与分桶余量。
 */
@Slf4j
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
@Validated
public class SeckillController {

    private final SeckillStockService stockService;
    private final SnowflakeIdGenerator idGen;
    private final RocketMQTemplate rocketMQTemplate;
    private final ProductClient productClient;

    /** 活动预热：按给定总库存建分桶。 */
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

    /** 秒杀下单：限流+幂等切面 -> Redis 分桶预扣 -> MQ。 */
    @RateLimit(limit = 5, windowSeconds = 1)
    @Idempotent
    @PostMapping("/orders")
    public Result<Map<String, Object>> seckill(@RequestBody @Validated SeckillReq req) {
        Long userId = UserContext.get();
        // 商品存在性（Feign→mall-product，走其多级缓存）
        Result<Map<String, Object>> pr = productClient.detail(req.getProductId());
        if (pr.getCode() != 0) {
            throw BizException.of("商品不存在");
        }

        int bucket = stockService.tryDeduct(req.getProductId(), userId, req.getQuantity());
        if (bucket == -1) {
            throw new BizException(409, "您已参与过本场活动");
        }
        if (bucket == -2) {
            throw new BizException(409, "已售罄");
        }
        String orderNo = idGen.nextOrderNo();
        try {
            rocketMQTemplate.syncSend(MqTopics.ORDER_CREATE_TOPIC,
                    MessageBuilder.withPayload(new SeckillMessage(
                            orderNo, userId, req.getProductId(), req.getQuantity(),
                            bucket, req.getAddressId())).build());
        } catch (Exception e) {
            log.error("mq send failed, restore bucket. orderNo={}", orderNo, e);
            stockService.restore(req.getProductId(), userId, bucket, req.getQuantity());
            throw new BizException(500, "下单繁忙，请重试");
        }
        log.info("seckill accepted, orderNo={}, product={}, bucket={}", orderNo, req.getProductId(), bucket);
        return Result.ok(Map.of("orderNo", orderNo, "status", "ACCEPTED"));
    }

    /** 分桶合计余量（对账）。 */
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
