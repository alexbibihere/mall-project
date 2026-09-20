package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    /** 状态机：UNPAID -> PAID；UNPAID -> CANCELLED；非法流转拒绝 */
    public static final String ST_UNPAID = "UNPAID";
    public static final String ST_PAID = "PAID";
    public static final String ST_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String orderNo;
    private String addressSnapshot;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
}
