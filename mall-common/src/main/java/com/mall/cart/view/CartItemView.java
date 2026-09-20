package com.mall.cart.view;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车行视图（common 化的跨服务 DTO 占位：供网关聚合使用）。
 */
@Data
public class CartItemView {
    private Long productId;
    private String name;
    private BigDecimal price;
    private int quantity;
    private boolean checked;
}
