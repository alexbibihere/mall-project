package com.mall.user.controller;

import com.mall.common.Result;
import com.mall.common.UserContext;
import com.mall.user.entity.Address;
import com.mall.user.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public Result<List<Address>> list() {
        return Result.ok(addressService.listMine());
    }

    @PostMapping
    public Result<Long> add(@RequestBody Address addr) {
        return Result.ok(addressService.add(addr));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Address addr) {
        addr.setId(id);
        addressService.update(addr);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(id);
        return Result.ok();
    }
}
