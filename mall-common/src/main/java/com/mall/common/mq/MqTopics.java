package com.mall.common.mq;

/**
 * MQ Topic/消费组常量（跨服务共享）。
 */
public final class MqTopics {

    public static final String ORDER_CREATE_TOPIC = "SECKILL_ORDER_TOPIC";
    public static final String CONSUMER_GROUP = "mall-order-consumer-group";

    /** M2.3: 商品变更事件（product 发 -> search 消费同步 ES）。快照全量字段，消费端直接 upsert。 */
    public static final String PRODUCT_CHANGED_TOPIC = "PRODUCT_CHANGED_TOPIC";
    public static final String SEARCH_CONSUMER_GROUP = "mall-search-consumer-group";

    /** M3.1: 同步下单库存扣减任务（order 发 -> order 消费，最终一致扣减）。 */
    public static final String ORDER_STOCK_DEDUCT_TOPIC = "ORDER_STOCK_DEDUCT_TOPIC";
    public static final String STOCK_DEDUCT_CONSUMER_GROUP = "mall-order-stock-deduct-group";

    private MqTopics() {
    }
}
