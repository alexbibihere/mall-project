package com.mall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.BizException;
import com.mall.order.entity.Order;
import com.mall.order.entity.Payment;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 支付服务：M1 mock 渠道。
 * 幂等核心：回调先 CAS 支付单 UNPAID->PAID，抢不到=重复回调，直接幂等返回成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 创建支付单（同一订单存在未过期 UNPAID 支付单则复用）。 */
    @Transactional
    public Payment create(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw BizException.of("订单不存在");
        }
        if (!Order.ST_UNPAID.equals(order.getStatus())) {
            throw BizException.of(409, "订单状态不可支付: " + order.getStatus());
        }
        Payment existing = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, order.getId())
                .eq(Payment::getStatus, Payment.ST_UNPAID)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing; // 复用，防重复拉起支付
        }
        Payment p = new Payment();
        p.setOrderId(order.getId());
        p.setUserId(order.getUserId());
        p.setAmount(order.getPayAmount());
        p.setChannel("MOCK");
        p.setStatus(Payment.ST_UNPAID);
        paymentMapper.insert(p);
        return p;
    }

    /**
     * mock 支付渠道回调。
     * 三重防资损：①CAS 幂等（重复回调只生效一次）②金额校验 ③订单状态机二次 CAS。
     */
    @Transactional
    public Map<String, Object> mockNotify(Long paymentId) {
        Payment p = paymentMapper.selectById(paymentId);
        if (p == null) {
            throw BizException.of("支付单不存在");
        }
        // ① 支付单幂等：CAS 抢锁，抢不到说明已处理过——幂等成功返回
        int affected = paymentMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payment>()
                        .eq(Payment::getId, paymentId)
                        .eq(Payment::getStatus, Payment.ST_UNPAID)
                        .set(Payment::getStatus, Payment.ST_PAID)
                        .set(Payment::getPaidAt, LocalDateTime.now())
                        .set(Payment::getCallbackPayload, "mock-notify"));
        if (affected == 0) {
            log.info("duplicate callback ignored, paymentId={}", paymentId);
            return Map.of("duplicate", true);
        }

        Order order = orderMapper.selectById(p.getOrderId());
        // ② 金额校验：回调金额 ≠ 应付金额即为异常，绝不放行
        if (order == null || order.getPayAmount().compareTo(p.getAmount()) != 0) {
            throw BizException.of(500, "回调金额异常");
        }
        // ③ 订单状态机 CAS：UNPAID -> PAID；与取消/关单并发时只有一方胜出
        int orderAffected = orderMapper.casStatus(order.getOrderNo(), Order.ST_UNPAID, Order.ST_PAID);
        if (orderAffected == 0) {
            // 订单已被取消但钱已付：M1 记日志走人工；M2 自动转退款单
            log.warn("CONFLICT: paid but order cancelled, orderNo={}", order.getOrderNo());
            throw BizException.of(409, "订单已取消，支付转退款处理");
        }
        log.info("order PAID, orderNo={}", order.getOrderNo());
        return Map.of("duplicate", false, "orderNo", order.getOrderNo());
    }

    /** 查单补偿（回调丢失场景）：渠道侧已支付则补单。 */
    public Map<String, Object> queryAndCompensate(Long paymentId) {
        Payment p = paymentMapper.selectById(paymentId);
        if (p == null) {
            throw BizException.of("支付单不存在");
        }
        if (Payment.ST_PAID.equals(p.getStatus())) {
            return Map.of("status", "PAID");
        }
        // mock 渠道：补偿接口即主动触发回调（真实实现=调渠道查单 API）
        return mockNotify(paymentId);
    }
}
