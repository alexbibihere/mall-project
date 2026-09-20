package com.mall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.SnowflakeIdGenerator;
import com.mall.order.entity.Order;
import com.mall.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 超时关单任务：每 30s 扫描 UNPAID 且超 15 分钟的订单。
 * M1.5 升级：Redisson 看门狗锁（默认 30s 自动续期），替代手写 setnx+TTL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutReaper {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final RedissonClient redisson;

    @Scheduled(fixedDelay = 30_000)
    public void reap() {
        List<Order> candidates = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, Order.ST_UNPAID)
                .lt(Order::getCreatedAt, LocalDateTime.now().minusMinutes(15))
                .last("LIMIT 200"));
        for (Order order : candidates) {
            RLock lock = redisson.getLock("lock:order:close:" + order.getOrderNo());
            boolean locked = false;
            try {
                locked = lock.tryLock(0, TimeUnit.SECONDS); // 不等待，抢不到说明他人在处理
                if (!locked) {
                    continue;
                }
                if (orderService.closeIfTimeout(order.getOrderNo())) {
                    log.info("order closed by timeout, orderNo={}", order.getOrderNo());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("close order failed, orderNo={}", order.getOrderNo(), e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
