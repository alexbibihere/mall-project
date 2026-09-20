package com.mall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.BizException;
import com.mall.common.Result;
import com.mall.common.SnowflakeIdGenerator;
import com.mall.common.UserContext;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.feign.ProductInternalClient;
import com.mall.order.feign.UserInternalClient;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;

import java.math.BigDecimal;
import java.util.List;
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

    /** 下单入参项（服务间解耦后不再依赖购物车 DTO）。 */
    public record OrderItemInput(Long productId, int quantity) {
    }

    /**
     * M2.2：Seata AT 全局事务（TM=order，分支=order本地库 + product扣库存）。
     * 异常时 TC 驱动 product 分支按 undo_log 自动回补，替代 M2.1 的手写补偿循环。
     */
    @GlobalTransactional(name = "mall-order-create", rollbackFor = Exception.class, timeoutMills = 60000)
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
            oi.setProductId(Long.valueOf(String.valueOf(p.get("id")))); // JSON 数字可能反序列化为 Integer，统一经字符串转
            oi.setName(String.valueOf(p.get("name")));
            oi.setPrice(new BigDecimal(String.valueOf(p.get("price"))));
            oi.setQuantity(i.quantity());
            oi.setSubtotal(oi.getPrice().multiply(BigDecimal.valueOf(i.quantity())));
            return oi;
        }).toList();

        // 3. 乐观锁扣库存（Feign → mall-product，注册为 Seata AT 分支）
        //    任一商品失败：全局事务回滚，TC 驱动 product 按 undo_log 反向补偿（替代手写回补循环）
        log.info("global tx begin, xid={}", RootContext.getXID());
        for (OrderItem oi : orderItems) {
            Result<Boolean> dr = productInternalClient.deduct(oi.getProductId(), oi.getQuantity());
            if (dr.getCode() != 0 || !Boolean.TRUE.equals(dr.getData())) {
                throw BizException.of(409, "库存不足");
            }
        }

        // 4. 订单落库（快照固化）
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(idGen.nextOrderNo());
        order.setAddressSnapshot(addressSnapshot);
        BigDecimal total = orderItems.stream().map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(total);
        order.setPayAmount(total);
        order.setStatus(Order.ST_UNPAID);
        orderMapper.insert(order);

        orderItems.forEach(oi -> oi.setOrderId(order.getId()));
        orderItems.forEach(orderItemMapper::insert);
        return order;
    }

    /** 用户主动取消（仅 UNPAID）；CAS 保证与支付回调并发时只有一方生效。 */
    public void cancel(Long userId, String orderNo) {
        mustOwn(userId, orderNo);
        int affected = orderMapper.casStatus(orderNo, Order.ST_UNPAID, Order.ST_CANCELLED);
        if (affected == 0) {
            throw BizException.of(409, "订单状态已变更，无法取消");
        }
        restoreStockByOrderNo(orderNo);
    }

    /** 超时关单（定时任务调用，无用户上下文）。 */
    public boolean closeIfTimeout(String orderNo) {
        int affected = orderMapper.casStatus(orderNo, Order.ST_UNPAID, Order.ST_CANCELLED);
        if (affected == 0) {
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
