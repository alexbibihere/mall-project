package com.mall.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.Result;
import com.mall.common.UserContext;
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

/**
 * 订单接口（M2）：fromCart 语义改为「服务端按勾选+数量入参」，
 * 购物车勾选数据由网关合并请求或前端直传（M2.1 简化：前端传 items）。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody @Valid CreateReq req) {
        Long userId = UserContext.get();
        Order order = orderService.create(userId, req.getAddressId(), req.toInputs());
        return Result.ok(Map.of(
                "orderNo", order.getOrderNo(),
                "payAmount", order.getPayAmount(),
                "status", order.getStatus()
        ));
    }

    @GetMapping
    public Result<Page<Order>> mine(@RequestParam(defaultValue = "1") long pageNum,
                                    @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(orderService.pageMine(UserContext.get(), pageNum, pageSize));
    }

    @GetMapping("/{orderNo}")
    public Result<Map<String, Object>> detail(@PathVariable String orderNo) {
        Order order = orderService.detail(UserContext.get(), orderNo);
        List<OrderItem> items = orderService.items(order.getId());
        return Result.ok(Map.of("order", order, "items", items));
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(UserContext.get(), orderNo);
        return Result.ok();
    }

    @Data
    public static class CreateReq {
        @NotNull
        private Long addressId;
        @Valid
        @NotNull
        private List<Item> items;

        public List<OrderService.OrderItemInput> toInputs() {
            return items.stream()
                    .map(i -> new OrderService.OrderItemInput(i.getProductId(), i.getQuantity()))
                    .toList();
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
