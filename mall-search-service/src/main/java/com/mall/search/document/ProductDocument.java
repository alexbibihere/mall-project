package com.mall.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品索引文档（mall_products）：文本字段走 IK 分词，keyword 子字段用于精确过滤/聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDocument {

    private Long id;

    /** ik_max_word 建索引（细粒度），ik_smart 检索（粗粒度防歧义爆炸）——经典 IK 最佳实践 */
    private String name;

    private String category;

    private String brand;

    private BigDecimal price;

    private Integer stock;
}
