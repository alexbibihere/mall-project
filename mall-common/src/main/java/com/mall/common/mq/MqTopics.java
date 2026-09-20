package com.mall.common.mq;

/**
 * MQ Topic/消费组常量（跨服务共享）。
 */
public final class MqTopics {

    public static final String ORDER_CREATE_TOPIC = "SECKILL_ORDER_TOPIC";
    public static final String CONSUMER_GROUP = "mall-order-consumer-group";

    private MqTopics() {
    }
}
