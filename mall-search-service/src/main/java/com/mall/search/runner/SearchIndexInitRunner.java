package com.mall.search.runner;

import com.mall.common.Result;
import com.mall.common.mq.ProductChangedMessage;
import com.mall.search.feign.ProductDataClient;
import com.mall.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M2.3: 启动即建索引（幂等）+ 全量对账一次（保证 ES 与 DB 最终一致，量级小直接全量）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitRunner implements CommandLineRunner {

    private final ProductSearchService searchService;
    private final ProductDataClient productDataClient;

    @Override
    public void run(String... args) {
        try {
            searchService.ensureIndex();
            Result<List<ProductChangedMessage>> r = productDataClient.all();
            if (r.getCode() == 0 && r.getData() != null) {
                int n = searchService.reindex(r.getData());
                log.info("startup reindex done, {} docs", n);
            } else {
                log.warn("startup reindex skipped: {}", r.getMessage());
            }
        } catch (Exception e) {
            log.error("search init failed (service still starts, reindex can be retried)", e);
        }
    }
}
