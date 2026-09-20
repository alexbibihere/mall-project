package com.mall.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.mq.MqTopics;
import com.mall.order.entity.OrderStockTask;
import com.mall.order.mapper.OrderStockTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存扣减补偿任务（M3.1）：本地消息表的「重发轮」。
 * 每 10s 扫 INIT 滞留（发送失败/漏发）与 PROCESSING 滞留（消费中断），重发 MQ。
 * 消费端 CAS 抢任务保证重发不会重复扣减；重试超限 FAILED 告警人工。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockTaskCompensator {

    private final OrderStockTaskMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelay = 10_000)
    public void resend() {
        List<OrderStockTask> stuck = taskMapper.selectList(new LambdaQueryWrapper<OrderStockTask>()
                .in(OrderStockTask::getStatus, OrderStockTask.ST_INIT, OrderStockTask.ST_PROCESSING)
                .lt(OrderStockTask::getUpdatedAt, LocalDateTime.now().minusSeconds(30))
                .last("LIMIT 100"));
        for (OrderStockTask t : stuck) {
            try {
                rocketMQTemplate.syncSend(MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
                        MessageBuilder.withPayload(t.getId()).build());
            } catch (Exception e) {
                log.warn("compensate resend failed taskId={}", t.getId());
            }
        }
        if (!stuck.isEmpty()) {
            log.info("compensator resent {} stuck stock tasks", stuck.size());
        }
    }
}
