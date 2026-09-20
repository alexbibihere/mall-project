package com.mall.order.service;

import com.mall.common.BizException;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderStatusLog;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 订单状态机（M3）：集中校验合法流转 + CAS 执行 + 流水审计。
 *
 * 状态流转图：
 *   UNPAID ──PAY_NOTIFY──▶ PAID
 *   UNPAID ──USER_CANCEL / TIMEOUT_REAPER──▶ CANCELLED
 *   PAID   ──REFUND──▶ CANCELLED（退款闭环，预留）
 *
 * 非法流转直接拒绝；CAS 保证并发下不跳变；流水只增不改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateMachine {

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper logMapper;

    /** 合法性校验：from -> to 是否允许。 */
    public static boolean isAllowed(String from, String to) {
        if (Order.ST_UNPAID.equals(from)) {
            return Order.ST_PAID.equals(to) || Order.ST_CANCELLED.equals(to);
        }
        if (Order.ST_PAID.equals(from)) {
            return Order.ST_CANCELLED.equals(to); // 退款闭环预留
        }
        return false; // CANCELLED 为终态
    }

    /**
     * CAS 流转 + 流水记录（同一事务）。
     * @return true=流转成功；false=状态已变更（调用方按幂等/冲突处理）
     */
    public boolean transit(String orderNo, String from, String to, String event, String operator) {
        if (!isAllowed(from, to)) {
            throw BizException.of(409, "非法状态流转: " + from + " -> " + to);
        }
        int affected = orderMapper.casStatus(orderNo, from, to);
        if (affected == 0) {
            return false;
        }
        OrderStatusLog line = new OrderStatusLog();
        line.setOrderNo(orderNo);
        line.setFromStatus(from);
        line.setToStatus(to);
        line.setEvent(event);
        line.setOperator(operator == null ? "system" : operator);
        line.setCreatedAt(LocalDateTime.now());
        logMapper.insert(line);
        return true;
    }

    /** 便捷法：流转失败即抛 409（用户主动操作场景）。 */
    public void transitOrThrow(String orderNo, String from, String to, String event, String operator) {
        if (!transit(orderNo, from, to, event, operator)) {
            throw BizException.of(409, "订单状态已变更，操作失败");
        }
    }
}
