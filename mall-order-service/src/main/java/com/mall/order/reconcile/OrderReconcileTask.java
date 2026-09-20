package com.mall.order.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.Result;
import com.mall.common.mq.MqTopics;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderStockTask;
import com.mall.order.feign.ProductInternalClient;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.OrderStockTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M3.3 对账中心 · 订单状态闭环对账（每 5 分钟）。
 *
 * 三条闭环校验（跨分片表全扫，单机数据量内可接受）：
 * ① 订单终态一致性：CANCELLED 订单的库存任务必须是终态（DONE 会被回补/FAILED），不得滞留 INIT/PROCESSING；
 * ② 本地消息表必达：UNPAID 订单的扣减任务滞留 INIT 超 5 分钟 → 重发 MQ（兜底 StockTaskCompensator 之外的第二道）；
 * ③ PAID 订单扣减必须 DONE：PAID 说明钱已收，扣减任务若非 DONE 属资损风险 → 告警日志（人工介入位）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconcileTask {

    private final OrderMapper orderMapper;
    private final OrderStockTaskMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelay = 300_000)
    public void reconcile() {
        long t0 = System.currentTimeMillis();
        int fixedResend = 0, closedTaskResidue = 0, paidNotDone = 0;

        // ①+② 以订单为锚扫任务（订单量 << 任务量，反查成本可控）
        List<Order> recent = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .in(Order::getStatus, Order.ST_UNPAID, Order.ST_CANCELLED, Order.ST_PAID)
                .last("LIMIT 500"));

        for (Order order : recent) {
            List<OrderStockTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<OrderStockTask>()
                    .eq(OrderStockTask::getOrderNo, order.getOrderNo()));
            if (tasks.isEmpty()) {
                continue;
            }

            for (OrderStockTask t : tasks) {
                switch (order.getStatus()) {
                    case Order.ST_CANCELLED -> {
                        // ① 已关订单任务必须终态
                        if (OrderStockTask.ST_INIT.equals(t.getStatus())
                                || OrderStockTask.ST_PROCESSING.equals(t.getStatus())) {
                            closedTaskResidue++;
                            OrderStockTask f = new OrderStockTask();
                            f.setId(t.getId());
                            f.setStatus(OrderStockTask.ST_FAILED);
                            f.setFailReason("RECONCILE: order already cancelled");
                            taskMapper.updateById(f);
                        }
                    }
                    case Order.ST_UNPAID -> {
                        // ② 待支付订单任务滞留 INIT → 重发
                        if (OrderStockTask.ST_INIT.equals(t.getStatus())) {
                            fixedResend++;
                            rocketMQTemplate.syncSend(MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
                                    MessageBuilder.withPayload(t.getId()).build());
                        }
                    }
                    case Order.ST_PAID -> {
                        // ③ 已支付订单扣减必须 DONE（资损风险位）
                        if (!OrderStockTask.ST_DONE.equals(t.getStatus())) {
                            paidNotDone++;
                            log.error("[RECONCILE-ALERT] PAID order has non-DONE stock task, orderNo={}, taskId={}, status={}",
                                    order.getOrderNo(), t.getId(), t.getStatus());
                        }
                    }
                    default -> { }
                }
            }
        }

        if (fixedResend > 0 || closedTaskResidue > 0 || paidNotDone > 0) {
            log.info("[RECONCILE] order loop done in {}ms: resent={}, closedResidueFixed={}, paidAlert={}",
                    System.currentTimeMillis() - t0, fixedResend, closedTaskResidue, paidNotDone);
        }
    }
}
