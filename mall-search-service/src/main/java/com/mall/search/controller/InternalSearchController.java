package com.mall.search.controller;

import com.mall.common.Result;
import com.mall.common.mq.ProductChangedMessage;
import com.mall.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 服务间内部接口（网关不路由 /internal/**）：全量对账重建索引。
 */
@Slf4j
@RestController
@RequestMapping("/internal/search")
@RequiredArgsConstructor
public class InternalSearchController {

    private final ProductSearchService searchService;

    /** 全量重建：body 为 product 全量快照（由调用方拉 DB 组装）。 */
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex(@RequestBody List<ProductChangedMessage> products) throws Exception {
        int n = searchService.reindex(products);
        return Result.ok(Map.of("indexed", n));
    }
}
