package com.mall.order.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.Result;
import com.mall.common.mq.MqTopics;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderStockTask;
import com.mall.order.feign.ProductInternalClient;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.OrderStockTaskMapper;
import com.mall.order.service.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存扣减任务消费者（M3.1）：
 * 1. CAS 抢任务（INIT/FAILED -> PROCESSING）：抢不到 = 已被处理，天然幂等；
 * 2. Feign 调 product 真扣减；成功 DONE；
 * 3. 库存不足：等同单兄弟任务到终态 → 回补已 DONE 的行（防超补）→ 状态机关单，任务 FAILED 终态。
 * 补偿任务重发消息时，CAS 保证不会重复扣减。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
        consumerGroup = MqTopics.STOCK_DEDUCT_CONSUMER_GROUP)
public class StockDeductTaskConsumer implements RocketMQListener<Long> {

    private final OrderStockTaskMapper taskMapper;
    private final OrderMapper orderMapper;
    private final ProductInternalClient productInternalClient;
    private final OrderStateMachine stateMachine;

    @Override
    public void onMessage(Long taskId) {
        OrderStockTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("stock task not found, id={}", taskId);
            return;
        }
        // 幂等核心：CAS 抢任务，抢不到说明已被处理过
        if (taskMapper.casGrab(taskId) == 0) {
            log.info("task {} grabbed by others, skip", taskId);
            return;
        }

        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, task.getOrderNo()));
        if (order == null || !Order.ST_UNPAID.equals(order.getStatus())) {
            fail(task, "order not payable: " + (order == null ? "GONE" : order.getStatus()));
            return;
        }

        // 真扣减（product 乐观锁兜底）
        Result<Boolean> r = productInternalClient.deduct(task.getProductId(), task.getQuantity());
        if (r.getCode() == 0 && Boolean.TRUE.equals(r.getData())) {
            OrderStockTask done = new OrderStockTask();
            done.setId(taskId);
            done.setStatus(OrderStockTask.ST_DONE);
            taskMapper.updateById(done);
            log.info("stock deducted, taskId={} orderNo={} productId={}",
                    taskId, task.getOrderNo(), task.getProductId());
            return;
        }

        // 库存不足：等待兄弟任务终态 → 只回补已 DONE 行（防超补）→ 关单
        log.warn("insufficient stock, orderNo={} productId={}", task.getOrderNo(), task.getProductId());
        waitSiblingsTerminal(task);
        restoreDoneSiblings(task);
        boolean closed = stateMachine.transit(task.getOrderNo(),
                Order.ST_UNPAID, Order.ST_CANCELLED, "STOCK_INSUFFICIENT", "system");
        fail(task, "INSUFFICIENT");
        log.info("order closed by stock-insufficient, orderNo={} closed={}", task.getOrderNo(), closed);
    }

    /** 等待同订单其他任务进入终态（防「关单回补」与「扣减中」竞态）；最多 ~6s。 */
    private void waitSiblingsTerminal(OrderStockTask task) {
        for (int i = 0; i < 30; i++) {
            Long pending = taskMapper.selectCount(new LambdaQueryWrapper<OrderStockTask>()
                    .eq(OrderStockTask::getOrderNo, task.getOrderNo())
                    .ne(OrderStockTask::getId, task.getId())
                    .in(OrderStockTask::getStatus, OrderStockTask.ST_INIT, OrderStockTask.ST_PROCESSING));
            if (pending == null || pending == 0) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("siblings not terminal after 6s, orderNo={}", task.getOrderNo());
    }

    /** 只回补「已成功扣减」的兄弟行；当前失败行从未扣过，不能补（防超加）。 */
    private void restoreDoneSiblings(OrderStockTask task) {
        List<OrderStockTask> done = taskMapper.selectList(new LambdaQueryWrapper<OrderStockTask>()
                .eq(OrderStockTask::getOrderNo, task.getOrderNo())
                .eq(OrderStockTask::getStatus, OrderStockTask.ST_DONE)
                .ne(OrderStockTask::getId, task.getId()));
        for (OrderStockTask d : done) {
            Result<Void> r = productInternalClient.restore(d.getProductId(), d.getQuantity());
            log.info("restore done-sibling on close, orderNo={} productId={} ok={}",
                    task.getOrderNo(), d.getProductId(), r.getCode() == 0);
        }
    }

    private void fail(OrderStockTask task, String reason) {
        OrderStockTask f = new OrderStockTask();
        f.setId(task.getId());
        f.setStatus(OrderStockTask.ST_FAILED);
        f.setFailReason(reason);
        taskMapper.updateById(f);
    }
}
