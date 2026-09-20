package com.mall.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.Result;
import com.mall.product.entity.Product;
import com.mall.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public Result<Page<Product>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String keyword) {
        return Result.ok(productService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    public Result<Product> detail(@PathVariable Long id) {
        return Result.ok(productService.detail(id));
    }
}
