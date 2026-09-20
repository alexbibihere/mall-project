package com.mall.search.consumer;

import com.mall.common.mq.MqTopics;
import com.mall.common.mq.ProductChangedMessage;
import com.mall.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 商品变更事件消费：upsert 到 ES（at-most-once，失败仅记日志，靠 reindex 对账兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.PRODUCT_CHANGED_TOPIC,
        consumerGroup = MqTopics.SEARCH_CONSUMER_GROUP)
public class ProductChangedConsumer implements RocketMQListener<ProductChangedMessage> {

    private final ProductSearchService searchService;

    @Override
    public void onMessage(ProductChangedMessage message) {
        try {
            searchService.upsert(message);
            log.info("es upserted product id={} name={}", message.getId(), message.getName());
        } catch (Exception e) {
            log.error("es upsert failed for product id={}, will be fixed by reindex", message.getId(), e);
        }
    }
}
