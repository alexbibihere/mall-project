package com.mall.product.controller;

import com.mall.common.Result;
import com.mall.common.mq.ProductChangedMessage;
import com.mall.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间内部接口：全量商品快照（供 search reindex 对账）。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductMapper productMapper;

    @GetMapping("/product/all")
    public Result<List<ProductChangedMessage>> all() {
        return Result.ok(productMapper.selectAllAsMessage());
    }
}
