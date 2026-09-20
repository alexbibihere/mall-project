package com.mall.product.mq;

import com.mall.common.mq.MqTopics;
import com.mall.common.mq.ProductChangedMessage;
import com.mall.product.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * M2.3: 商品变更事件发布（product -> search 同步 ES）。
 * at-most-once：发送失败仅记日志，不阻断主链路；一致性由 search 端 /internal/search/reindex 全量对账兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    public void publishChanged(Product p) {
        try {
            rocketMQTemplate.syncSend(MqTopics.PRODUCT_CHANGED_TOPIC,
                    MessageBuilder.withPayload(new ProductChangedMessage(
                            p.getId(), p.getName(), p.getCategory(), p.getBrand(),
                            p.getPrice(), p.getStock())).build());
        } catch (Exception e) {
            log.error("publish product changed event failed, id={} (reindex will fix)", p.getId(), e);
        }
    }
}
