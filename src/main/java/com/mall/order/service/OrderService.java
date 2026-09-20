package com.mall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.cart.service.CartService;
import com.mall.common.BizException;
import com.mall.common.SnowflakeIdGenerator;
import com.mall.common.UserContext;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.product.entity.Product;
import com.mall.product.service.StockService;
import com.mall.user.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单服务：M1 同步事务版下单核心链路。
 * 链路：校验 -> DB乐观锁扣库存 -> 订单+明细落库(快照) -> (购物车结算时)清已结算项。
 * M2 演进：扣库存改 Redis Lua 预扣 + RocketMQ 事务消息异步落库（结构已预留拆分点）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final StockService stockService;
    private final AddressService addressService;
    private final CartService cartService;
    private final SnowflakeIdGenerator idGen;
    private final StringRedisTemplate redis;

    /**
     * 创建订单。
     *
     * @param fromCart true=购物车勾选结算（服务端以购物车勾选项为权威，下单成功后移除）；
     *                 false=立即购买（items 由请求给定，不碰购物车）
     */
    @Transactional
    public Order create(Long addressId, List<CartService.CartItemView> directItems, boolean fromCart) {
        Long userId = UserContext.get();
        List<CartService.CartItemView> items;
        if (fromCart) {
            items = cartService.list().stream()
                    .filter(CartService.CartItemView::isChecked).toList();
            if (items.isEmpty()) {
                throw BizException.of("请先勾选要结算的商品");
            }
        } else {
            items = directItems;
            if (items == null || items.isEmpty()) {
                throw BizException.of("下单商品不能为空");
            }
        }
        if (items.size() > 50) {
            throw BizException.of("单笔订单最多 50 种商品");
        }
        String addressSnapshot = addressService.snapshot(addressId);

        // 1. 锁定单价与校验（以 DB 为价格权威，防前端传价）
        List<OrderItem> orderItems = items.stream().map(v -> {
            if (v.getQuantity() <= 0 || v.getQuantity() > 999) {
                throw BizException.of("购买数量非法");
            }
            Product p = stockService.getProduct(v.getProductId());
            OrderItem oi = new OrderItem();
            oi.setProductId(p.getId());
            oi.setName(p.getName());
            oi.setPrice(p.getPrice());
            oi.setQuantity(v.getQuantity());
            oi.setSubtotal(p.getPrice().multiply(BigDecimal.valueOf(v.getQuantity())));
            return oi;
        }).toList();

        // 2. 乐观锁扣库存（任意一件不足则整体失败回滚——@Transactional 保证）
        for (OrderItem oi : orderItems) {
            stockService.deduct(oi.getProductId(), oi.getQuantity());
        }

        // 3. 订单落库（地址/商品快照固化）
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(idGen.nextOrderNo());
        order.setAddressSnapshot(addressSnapshot);
        BigDecimal total = orderItems.stream().map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(total);
        order.setPayAmount(total); // M1 无营销优惠；M2 接营销试算
        order.setStatus(Order.ST_UNPAID);
        orderMapper.insert(order);

        orderItems.forEach(oi -> oi.setOrderId(order.getId()));
        orderItems.forEach(orderItemMapper::insert);

        // 4. 购物车结算路径：移除已结算项（立即购买不碰购物车）
        if (fromCart) {
            items.forEach(v -> cartService.remove(v.getProductId()));
        }
        return order;
    }

    /** 用户主动取消（仅 UNPAID）；CAS 保证与支付回调并发时只有一方生效。 */
    public void cancel(String orderNo) {
        mustOwn(orderNo); // 归属校验（登录上下文内）
        int affected = orderMapper.casStatus(orderNo, Order.ST_UNPAID, Order.ST_CANCELLED);
        if (affected == 0) {
            throw BizException.of(409, "订单状态已变更，无法取消");
        }
        restoreStockByOrderNo(orderNo);
    }

    /**
     * 超时关单（定时任务调用，无登录上下文，不做归属校验）。
     * CAS 幂等：已支付/已取消时 affected=0，直接放弃。
     */
    public boolean closeIfTimeout(String orderNo) {
        int affected = orderMapper.casStatus(orderNo, Order.ST_UNPAID, Order.ST_CANCELLED);
        if (affected == 0) {
            return false;
        }
        restoreStockByOrderNo(orderNo);
        return true;
    }

    public Page<Order> pageMine(long pageNum, long pageSize) {
        return orderMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Order>().eq(Order::getUserId, UserContext.get())
                        .orderByDesc(Order::getId));
    }

    public Order detail(String orderNo) {
        return mustOwn(orderNo);
    }

    public List<OrderItem> items(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
    }

    /** 供支付服务按内部流程取单（无归属校验）。 */
    public Order getByOrderNo(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
    }

    private Order mustOwn(String orderNo) {
        Order o = getByOrderNo(orderNo);
        if (o == null || !o.getUserId().equals(UserContext.get())) {
            throw BizException.of("订单不存在");
        }
        return o;
    }

    private void restoreStockByOrderNo(String orderNo) {
        Order o = getByOrderNo(orderNo);
        items(o.getId()).forEach(oi ->
                stockService.restore(oi.getProductId(), oi.getQuantity()));
    }
}
