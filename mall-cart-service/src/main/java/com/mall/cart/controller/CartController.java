package com.mall.cart.controller;

import com.mall.common.Result;
import com.mall.cart.service.CartService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Validated
public class CartController {

    private final CartService cartService;

    @GetMapping
    public Result<List<CartService.CartItemView>> list() {
        return Result.ok(cartService.list());
    }

    @PostMapping
    public Result<Void> add(@RequestBody @Validated AddReq req) {
        cartService.add(req.getProductId(), req.getQuantity());
        return Result.ok();
    }

    @PutMapping("/{productId}/qty/{qty}")
    public Result<Void> updateQty(@PathVariable Long productId, @PathVariable int qty) {
        cartService.updateQty(productId, qty);
        return Result.ok();
    }

    @PutMapping("/{productId}/check/{checked}")
    public Result<Void> check(@PathVariable Long productId, @PathVariable boolean checked) {
        cartService.check(productId, checked);
        return Result.ok();
    }

    @DeleteMapping("/{productId}")
    public Result<Void> remove(@PathVariable Long productId) {
        cartService.remove(productId);
        return Result.ok();
    }

    @Data
    public static class AddReq {
        @NotNull
        private Long productId;
        @Min(1)
        @Max(99)
        private int quantity;
    }
}
