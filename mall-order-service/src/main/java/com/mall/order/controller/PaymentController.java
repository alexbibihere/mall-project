package com.mall.order.controller;

import com.mall.common.Result;
import com.mall.order.entity.Payment;
import com.mall.order.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** 创建支付单（mock 渠道直接返回收银台参数）。 */
    @PostMapping("/orders/{orderNo}")
    public Result<Payment> create(@PathVariable String orderNo) {
        return Result.ok(paymentService.create(orderNo));
    }

    /** mock 渠道异步回调（生产环境需验签，此处模拟渠道行为）。 */
    @PostMapping("/mock/notify/{paymentId}")
    public Result<Map<String, Object>> mockNotify(@PathVariable Long paymentId) {
        return Result.ok(paymentService.mockNotify(paymentId));
    }

    /** 查单补偿：回调丢失时主动核对。 */
    @PostMapping("/{paymentId}/compensate")
    public Result<Map<String, Object>> compensate(@PathVariable Long paymentId) {
        return Result.ok(paymentService.queryAndCompensate(paymentId));
    }
}
