package com.mall.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.cart.service.CartService;
import com.mall.common.Result;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    /** 下单：fromCart=true 走购物车勾选结算（服务端权威）；false=立即购买（items 必填）。 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody @Valid CreateReq req) {
        Order order = orderService.create(req.getAddressId(),
                req.toItems(), req.isFromCart());
        return Result.ok(Map.of(
                "orderNo", order.getOrderNo(),
                "payAmount", order.getPayAmount(),
                "status", order.getStatus()
        ));
    }

    @GetMapping
    public Result<Page<Order>> mine(@RequestParam(defaultValue = "1") long pageNum,
                                    @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(orderService.pageMine(pageNum, pageSize));
    }

    @GetMapping("/{orderNo}")
    public Result<Map<String, Object>> detail(@PathVariable String orderNo) {
        Order order = orderService.detail(orderNo);
        List<OrderItem> items = orderService.items(order.getId());
        return Result.ok(Map.of("order", order, "items", items));
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(orderNo);
        return Result.ok();
    }

    @Data
    public static class CreateReq {
        @NotNull
        private Long addressId;
        private boolean fromCart;          // true=购物车勾选结算
        @Valid
        private List<Item> items;          // 立即购买时必填

        public List<CartService.CartItemView> toItems() {
            if (items == null) {
                return null;
            }
            return items.stream().map(i -> {
                CartService.CartItemView v = new CartService.CartItemView();
                v.setProductId(i.getProductId());
                v.setQuantity(i.getQuantity());
                v.setChecked(true);
                return v;
            }).toList();
        }
    }

    @Data
    public static class Item {
        @NotNull
        private Long productId;
        @NotNull
        private Integer quantity;
    }
}
