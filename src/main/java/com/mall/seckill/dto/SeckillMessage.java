package com.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀下单消息：扣减成功 -> 投递 -> 消费端落库。
 * orderNo 由生产端预生成（用户立即拿到受理单号），消费端直接用它建单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage {

    private String orderNo;
    private Long userId;
    private Long productId;
    private int quantity;
    private int bucketIdx;   // 扣中的分桶（失败回补时定位）
    private Long addressId;
}
