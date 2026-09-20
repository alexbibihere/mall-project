package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment")
public class Payment {

    public static final String ST_UNPAID = "UNPAID";
    public static final String ST_PAID = "PAID";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private String channel;
    private String status;
    private String callbackPayload;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
}
