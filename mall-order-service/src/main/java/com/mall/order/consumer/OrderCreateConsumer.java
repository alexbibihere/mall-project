package com.mall.order.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.BizException;
import com.mall.common.Result;
import com.mall.common.mq.SeckillMessage;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.feign.ProductInternalClient;
import com.mall.order.feign.SeckillInternalClient;
import com.mall.order.feign.UserInternalClient;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 秒杀订单消费者（归订单服务，M2）：Feign 取快照 + Feign 扣减兜底 + Feign 回补。
 * 顺序：幂等 -> 地址快照 -> DB 扣减 -> 落库；失败语义同 M1.5 设计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = com.mall.common.mq.MqTopics.ORDER_CREATE_TOPIC,
        consumerGroup = com.mall.common.mq.MqTopics.CONSUMER_GROUP
)
public class OrderCreateConsumer implements RocketMQListener<SeckillMessage> {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductInternalClient productClient;
    private final UserInternalClient userClient;
    private final SeckillInternalClient seckillClient;

    @Override
    public void onMessage(SeckillMessage msg) {
        try {
            handle(msg);
        } catch (StockGiveUpException | BizException e) {
            // 业务不可达：回补 Redis 分桶（Feign→mall-seckill），消息确认消费
            log.warn("giveup, restore bucket. orderNo={}, reason={}", msg.getOrderNo(), e.getMessage());
            try {
                seckillClient.restore(Map.of(
                        "productId", msg.getProductId(),
                        "userId", msg.getUserId(),
                        "bucketIdx", msg.getBucketIdx(),
                        "quantity", msg.getQuantity()));
            } catch (Exception ex) {
                log.error("bucket restore failed, orderNo={}", msg.getOrderNo(), ex);
            }
        }
    }

    @Transactional
    public void handle(SeckillMessage msg) {
        // 1. 幂等
        Long exists = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, msg.getOrderNo()));
        if (exists > 0) {
            log.info("duplicate seckill message ignored, orderNo={}", msg.getOrderNo());
            return;
        }

        // 2. 地址快照（Feign→mall-user；失败=业务不可达，向上抛由 onMessage 回补分桶）
        Result<String> addr = userClient.snapshot(msg.getAddressId(), msg.getUserId());
        if (addr.getCode() != 0) {
            throw new BizException(404, "地址不存在");
        }

        // 3. DB 乐观锁扣减（Feign→mall-product）
        Result<Boolean> deducted = productClient.deduct(msg.getProductId(), msg.getQuantity());
        if (deducted.getCode() != 0 || !Boolean.TRUE.equals(deducted.getData())) {
            throw new StockGiveUpException();
        }

        try {
            // 4. 商品快照 + 落库
            Result<Map<String, Object>> pr = productClient.product(msg.getProductId());
            if (pr.getCode() != 0 || pr.getData() == null) {
                throw new BizException(404, "商品不存在");
            }
            Map<String, Object> p = pr.getData();
            BigDecimal price = new BigDecimal(String.valueOf(p.get("price")));
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(msg.getQuantity()));

            Order order = new Order();
            order.setUserId(msg.getUserId());
            order.setOrderNo(msg.getOrderNo());
            order.setAddressSnapshot(addr.getData());
            order.setTotalAmount(subtotal);
            order.setPayAmount(subtotal);
            order.setStatus(Order.ST_UNPAID);
            orderMapper.insert(order);

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(msg.getProductId());
            item.setName(String.valueOf(p.get("name")));
            item.setPrice(price);
            item.setQuantity(msg.getQuantity());
            item.setSubtotal(subtotal);
            orderItemMapper.insert(item);
            log.info("seckill order created, orderNo={}, userId={}", msg.getOrderNo(), msg.getUserId());
        } catch (Exception e) {
            // 落库失败：回补 DB 再抛出走 broker 重试
            productClient.restore(msg.getProductId(), msg.getQuantity());
            throw e;
        }
    }

    static class StockGiveUpException extends RuntimeException {
    }
}
