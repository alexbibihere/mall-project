package com.mall.order.feign;

import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 秒杀服务内部接口：分桶回补。
 */
@FeignClient(name = "mall-seckill", contextId = "seckillInternalClient")
public interface SeckillInternalClient {

    @PostMapping("/internal/seckill/restore")
    Result<Void> restore(@RequestBody Map<String, Object> req);
}
