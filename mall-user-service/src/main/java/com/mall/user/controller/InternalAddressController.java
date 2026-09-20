package com.mall.user.controller;

import com.mall.common.Result;
import com.mall.user.entity.Address;
import com.mall.user.mapper.AddressMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 服务间内部接口：地址快照（订单服务下单时固化收货信息）。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalAddressController {

    private final AddressMapper addressMapper;

    @GetMapping("/address/{addressId}/snapshot")
    public Result<String> snapshot(@PathVariable Long addressId, @RequestParam Long userId) {
        Address a = addressMapper.selectById(addressId);
        if (a == null || !a.getUserId().equals(userId)) {
            return Result.error(404, "地址不存在");
        }
        return Result.ok(a.getReceiver() + " " + a.getPhone() + " "
                + a.getProvince() + a.getCity() + a.getDetail());
    }
}
