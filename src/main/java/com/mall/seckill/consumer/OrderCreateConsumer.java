package com.mall.seckill.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.BizException;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.product.entity.Product;
import com.mall.product.service.StockService;
import com.mall.seckill.config.MqConfig;
import com.mall.seckill.dto.SeckillMessage;
import com.mall.seckill.service.SeckillStockService;
import com.mall.user.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 秒杀订单消费者（RocketMQ 集群模式）：匀速落库，DB 乐观锁二次兜底。
 * 处理顺序（防重复扣减的关键）：幂等查重 -> 地址校验 -> DB 扣库存 -> 落库。
 * 任何「扣库存之前」的失败：直接放弃（无需回补，Redis 预扣原样保留语义不对——
 *   地址类失败属业务不可达，回补分桶让库存可售）。
 * 「扣库存之后」的失败（如落库异常）：回补 DB + 回补 Redis，再抛出走 broker 重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConfig.ORDER_CREATE_TOPIC,
        consumerGroup = MqConfig.CONSUMER_GROUP
)
public class OrderCreateConsumer implements RocketMQListener<SeckillMessage> {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final StockService stockService;
    private final SeckillStockService seckillStockService;
    private final AddressService addressService;

    @Override
    public void onMessage(SeckillMessage msg) {
        try {
            handle(msg);
        } catch (StockGiveUpException giveUp) {
            // DB 库存不足（理论不该发生）：回补 Redis 分桶，消息确认消费，不入死信
            log.warn("db stock giveup, restore bucket. orderNo={}", msg.getOrderNo());
            seckillStockService.restore(msg.getProductId(), msg.getUserId(), msg.getBucketIdx(), msg.getQuantity());
        } catch (BizException e) {
            // 业务不可达（如地址归属错误）：回补 Redis 分桶，消息确认消费，不入死信
            log.warn("biz giveup, restore bucket. orderNo={}, reason={}", msg.getOrderNo(), e.getMessage());
            seckillStockService.restore(msg.getProductId(), msg.getUserId(), msg.getBucketIdx(), msg.getQuantity());
        }
        // 其它异常原样抛出 -> broker 重试，重试由幂等查重保护
    }

    @Transactional
    public void handle(SeckillMessage msg) {
        // 1. 幂等：重复投递检查（唯一索引兜底并发）
        Long exists = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, msg.getOrderNo()));
        if (exists > 0) {
            log.info("duplicate seckill message ignored, orderNo={}", msg.getOrderNo());
            return;
        }

        // 2. 地址校验（放在扣库存之前——失败时不产生任何扣减）
        String addressSnapshot = addressService.snapshotFor(msg.getAddressId(), msg.getUserId());

        // 3. DB 乐观锁二次兜底（分桶预热总量 ≤ DB 库存，正常必然成功）
        if (!stockService.deductQuiet(msg.getProductId(), msg.getQuantity())) {
            throw new StockGiveUpException();
        }

        try {
            // 4. 落库（快照固化）
            Product p = stockService.getProduct(msg.getProductId());
            Order order = new Order();
            order.setUserId(msg.getUserId());
            order.setOrderNo(msg.getOrderNo());
            order.setAddressSnapshot(addressSnapshot);
            BigDecimal subtotal = p.getPrice().multiply(BigDecimal.valueOf(msg.getQuantity()));
            order.setTotalAmount(subtotal);
            order.setPayAmount(subtotal);
            order.setStatus(Order.ST_UNPAID);
            orderMapper.insert(order);

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(msg.getProductId());
            item.setName(p.getName());
            item.setPrice(p.getPrice());
            item.setQuantity(msg.getQuantity());
            item.setSubtotal(subtotal);
            orderItemMapper.insert(item);
            log.info("seckill order created, orderNo={}, userId={}", msg.getOrderNo(), msg.getUserId());
        } catch (Exception e) {
            // 落库失败：已扣的 DB 库存必须回补，再抛出交给 broker 重试
            stockService.restore(msg.getProductId(), msg.getQuantity());
            throw e;
        }
    }

    /** DB 库存不足：回补 Redis 后放弃（消费成功确认，避免无意义重试）。 */
    static class StockGiveUpException extends RuntimeException {
    }
}
