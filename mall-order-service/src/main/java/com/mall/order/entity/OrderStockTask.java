package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存扣减本地消息表（M3.1）：与订单同库同事务写入，保证「订单成功 ⇒ 扣减任务必达」。
 * 补偿任务重扫 INIT/FAILED 重发；消费端 CAS 抢任务保证幂等。
 */
@Data
@TableName("order_stock_task")
public class OrderStockTask {

    public static final String ST_INIT = "INIT";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_DONE = "DONE";
    public static final String ST_FAILED = "FAILED"; // 库存不足终态（订单已关）

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long productId;

    private Integer quantity;

    private String status;

    private Integer retries;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
