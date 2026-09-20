package com.mall.search.controller;

import com.mall.common.Result;
import com.mall.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 商品搜索（M2.3）：走网关 /api/search/**，公开接口（网关白名单放行）。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService searchService;

    @GetMapping
    public Result<Map<String, Object>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sort) throws Exception {
        return Result.ok(searchService.search(q, category, brand, minPrice, maxPrice, pageNum, pageSize, sort));
    }
}
