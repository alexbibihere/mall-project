package com.mall.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀下单消息（跨服务共享结构）。
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
