package com.mall.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品变更消息（M2.3 搜索同步）：product 变更后发快照，search 消费直接 upsert ES。
 * 语义 at-most-once：发送失败仅记日志，靠 /internal/search/reindex 全量对账兜底。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductChangedMessage {

    private Long id;
    private String name;
    private String category;
    private String brand;
    private BigDecimal price;
    private Integer stock;
}
