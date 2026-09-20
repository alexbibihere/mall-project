package com.mall.seckill.service;

import com.mall.common.BizException;
import com.mall.common.SnowflakeIdGenerator;
import com.mall.common.UserContext;
import com.mall.product.entity.Product;
import com.mall.product.service.StockService;
import com.mall.seckill.config.MqConfig;
import com.mall.seckill.dto.SeckillMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * 秒杀服务：限流/幂等(切面) -> Redis 分桶 Lua 原子预扣 -> MQ 异步落库。
 * 主链路零 DB 写，RPS 上限取决于 Redis 单机（10w+ QPS 量级）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final SeckillStockService stockService;
    private final StockService dbStockService;
    private final SnowflakeIdGenerator idGen;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 秒杀下单（前置切面已完成限流+幂等）。
     *
     * @return 受理单号（消费端落库后按此单号查询订单）
     */
    public String seckill(Long productId, int quantity, Long addressId) {
        Long userId = UserContext.get();
        Product product = dbStockService.getProduct(productId); // 校验商品存在性（走缓存）

        int bucket = stockService.tryDeduct(productId, userId, quantity);
        if (bucket == -1) {
            throw new BizException(409, "您已参与过本场活动");
        }
        if (bucket == -2) {
            throw new BizException(409, "已售罄");
        }
        String orderNo = idGen.nextOrderNo();
        // 同步发送（send-message-timeout=3s + 失败重试2次，yml 已配）；
        // 发送失败会抛异常 -> 事务回滚 Redis 预扣不会回滚，故 catch 后手动回补分桶
        try {
            rocketMQTemplate.syncSend(MqConfig.ORDER_CREATE_TOPIC,
                    MessageBuilder.withPayload(new SeckillMessage(
                            orderNo, userId, productId, quantity, bucket, addressId)).build());
        } catch (Exception e) {
            log.error("mq send failed, restore bucket. orderNo={}", orderNo, e);
            stockService.restore(productId, userId, bucket, quantity);
            throw new BizException(500, "下单繁忙，请重试");
        }
        log.info("seckill accepted, orderNo={}, product={}, bucket={}", orderNo, productId, bucket);
        return orderNo;
    }
}
