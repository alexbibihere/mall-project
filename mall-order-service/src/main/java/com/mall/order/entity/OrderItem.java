package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    /** M3.2 分片键冗余：路由跟随订单的 user_id */
    private Long userId;
    private Long productId;
    private String name;      // 商品名快照
    private BigDecimal price; // 成交单价快照
    private Integer quantity;
    private BigDecimal subtotal;
}
