package com.mall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.BizException;
import com.mall.common.Result;
import com.mall.common.SnowflakeIdGenerator;
import com.mall.common.UserContext;
import com.mall.common.mq.MqTopics;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.entity.OrderStockTask;
import com.mall.order.feign.ProductInternalClient;
import com.mall.order.feign.UserInternalClient;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.OrderStockTaskMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 订单服务（M2）：同步下单链路。
 * 跨服务调用全部 Feign：商品扣减/快照 → mall-product；地址快照 → mall-user。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductInternalClient productInternalClient;
    private final UserInternalClient userInternalClient;
    private final SnowflakeIdGenerator idGen;
    private final OrderStateMachine stateMachine;
    private final OrderStockTaskMapper stockTaskMapper;
    private final RocketMQTemplate rocketMQTemplate;

    /** 下单入参项（服务间解耦后不再依赖购物车 DTO）。 */
    public record OrderItemInput(Long productId, int quantity) {
    }

    /**
     * M3.1：下单改「本地消息表 + MQ 最终一致」（替代 M2.2 Seata AT）。
     * 实测 AT 使下单吞吐 -86.6%（QPS 167.6 -> 22.5，见 BENCH_M2_REPORT.md），
     * 故主链路回归：本地事务只做「校验+订单落库+任务表落库」，扣减经 MQ 异步化，
     * 消息可靠性由任务表 + 补偿重扫保证；库存不足由消费端关单回补。
     */
    @Transactional
    public Order create(Long userId, Long addressId, List<OrderItemInput> items) {
        if (items == null || items.isEmpty()) {
            throw BizException.of("下单商品不能为空");
        }
        if (items.size() > 50) {
            throw BizException.of("单笔订单最多 50 种商品");
        }

        // 1. 地址快照（Feign → mall-user）
        Result<String> addrResult = userInternalClient.snapshot(addressId, userId);
        if (addrResult.getCode() != 0) {
            throw BizException.of("地址不存在");
        }
        String addressSnapshot = addrResult.getData();

        // 2. 锁定单价（Feign → mall-product，以服务端为价格权威）
        List<OrderItem> orderItems = items.stream().map(i -> {
            if (i.quantity() <= 0 || i.quantity() > 999) {
                throw BizException.of("购买数量非法");
            }
            Result<Map<String, Object>> r = productInternalClient.product(i.productId());
            if (r.getCode() != 0 || r.getData() == null) {
                throw BizException.of("商品不存在: " + i.productId());
            }
            Map<String, Object> p = r.getData();
            OrderItem oi = new OrderItem();
            oi.setUserId(userId); // M3.2 分片键冗余
            oi.setProductId(Long.valueOf(String.valueOf(p.get("id")))); // JSON 数字可能反序列化为 Integer，统一经字符串转
            oi.setName(String.valueOf(p.get("name")));
            oi.setPrice(new BigDecimal(String.valueOf(p.get("price"))));
            oi.setQuantity(i.quantity());
            oi.setSubtotal(oi.getPrice().multiply(BigDecimal.valueOf(i.quantity())));
            return oi;
        }).toList();

        // 3. 乐观预检（Soft Check，不真扣）：秒杀链路已有分桶预扣口径，
        //    同步链路改为「先下单受理，库存扣减异步化」；明显不足时快速失败省一次下单
        for (OrderItem oi : orderItems) {
            Result<Map<String, Object>> pr = productInternalClient.product(oi.getProductId());
            if (pr.getCode() != 0 || pr.getData() == null) {
                throw BizException.of("商品不存在: " + oi.getProductId());
            }
            int stock = Integer.parseInt(String.valueOf(pr.getData().get("stock")));
            if (stock < oi.getQuantity()) {
                throw BizException.of(409, "库存不足");
            }
        }

        // 4. 订单落库（快照固化）
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(idGen.nextOrderNo(userId)); // 基因法：末位=user_id%10，分片路由用
        order.setAddressSnapshot(addressSnapshot);
        BigDecimal total = orderItems.stream().map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(total);
        order.setPayAmount(total);
        order.setStatus(Order.ST_UNPAID);
        orderMapper.insert(order);

        orderItems.forEach(oi -> oi.setOrderId(order.getId()));
        orderItems.forEach(orderItemMapper::insert);

        // 5. 本地消息表：与订单同事务落库（订单成功 => 扣减任务必达）
        List<OrderStockTask> tasks = new ArrayList<>();
        for (OrderItem oi : orderItems) {
            OrderStockTask t = new OrderStockTask();
            t.setOrderNo(order.getOrderNo());
            t.setProductId(oi.getProductId());
            t.setQuantity(oi.getQuantity());
            t.setStatus(OrderStockTask.ST_INIT);
            t.setRetries(0);
            stockTaskMapper.insert(t);
            tasks.add(t);
        }

        // 6. 事务提交后发 MQ（发送失败不回滚订单：补偿任务会重扫 INIT 重发，消息不丢）
        final List<OrderStockTask> sentTasks = tasks;
        final String sentOrderNo = order.getOrderNo();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (OrderStockTask t : sentTasks) {
                    try {
                        rocketMQTemplate.syncSend(MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
                                MessageBuilder.withPayload(t.getId()).build());
                    } catch (Exception e) {
                        log.warn("stock task mq send failed, taskId={} orderNo={} (补偿任务会重发)",
                                t.getId(), sentOrderNo);
                    }
                }
            }
        });
        return order;
    }

    /** 用户主动取消（仅 UNPAID）；状态机 CAS 保证与支付回调并发时只有一方生效。 */
    public void cancel(Long userId, String orderNo) {
        mustOwn(userId, orderNo);
        stateMachine.transitOrThrow(orderNo, Order.ST_UNPAID, Order.ST_CANCELLED,
                "USER_CANCEL", String.valueOf(userId));
        restoreStockByOrderNo(orderNo);
    }

    /** 超时关单（定时任务调用，无用户上下文）。 */
    public boolean closeIfTimeout(String orderNo) {
        boolean ok = stateMachine.transit(orderNo, Order.ST_UNPAID, Order.ST_CANCELLED,
                "TIMEOUT_REAPER", "system");
        if (!ok) {
            return false;
        }
        restoreStockByOrderNo(orderNo);
        return true;
    }

    public Page<Order> pageMine(Long userId, long pageNum, long pageSize) {
        return orderMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Order>().eq(Order::getUserId, userId)
                        .orderByDesc(Order::getId));
    }

    public Order detail(Long userId, String orderNo) {
        return mustOwn(userId, orderNo);
    }

    public List<OrderItem> items(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
    }

    public Order getByOrderNo(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
    }

    private Order mustOwn(Long userId, String orderNo) {
        Order o = getByOrderNo(orderNo);
        if (o == null || !o.getUserId().equals(userId)) {
            throw BizException.of("订单不存在");
        }
        return o;
    }

    private void restoreStockByOrderNo(String orderNo) {
        Order o = getByOrderNo(orderNo);
        items(o.getId()).forEach(oi ->
                productInternalClient.restore(oi.getProductId(), oi.getQuantity()));
    }
}
