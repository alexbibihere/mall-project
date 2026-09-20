package com.mall.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 拓扑说明（M1.5）：
 * - Topic: SECKILL_ORDER_TOPIC（生产端发送前由 mqadmin 显式创建；开发期 broker 开启自动创建亦可）
 * - 消费者组: mall-order-consumer-group（广播/集群模式默认集群=负载均衡）
 * - 消息体: SeckillMessage（JSON，rocketmq-spring 自带转换）
 * M2 演进：事务消息（半消息+回查）替换「先扣减后发送」；同步/异步落库分离双 Topic。
 */
@Configuration
public class MqConfig {

    public static final String ORDER_CREATE_TOPIC = "SECKILL_ORDER_TOPIC";
    public static final String CONSUMER_GROUP = "mall-order-consumer-group";
}
