package com.mall.order.feign;

import com.mall.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户服务内部接口：地址快照。
 */
@FeignClient(name = "mall-user", contextId = "userInternalClient")
public interface UserInternalClient {

    @GetMapping("/internal/address/{addressId}/snapshot")
    Result<String> snapshot(@PathVariable("addressId") Long addressId, @RequestParam("userId") Long userId);
}
