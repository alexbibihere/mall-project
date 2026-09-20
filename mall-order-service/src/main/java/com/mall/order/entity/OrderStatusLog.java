package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态流水（M3）：状态机每一次流转记一条（谁/何时/从哪到哪/触发事件）。
 * 只增不改（append-only），审计与对账依据。
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String fromStatus;

    private String toStatus;

    /** 触发来源：USER_CANCEL / PAY_NOTIFY / TIMEOUT_REAPER / SECKILL_ASYNC */
    private String event;

    /** 操作者：userId 或 system */
    private String operator;

    private LocalDateTime createdAt;
}
