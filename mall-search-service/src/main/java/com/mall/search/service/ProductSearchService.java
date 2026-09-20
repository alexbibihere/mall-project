package com.mall.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.mall.common.mq.ProductChangedMessage;
import com.mall.search.document.ProductDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

/**
 * 搜索服务（M2.3）：
 * - 索引自动创建（IK 分词 mapping，幂等）
 * - MQ 事件 upsert / 批量 reindex
 * - 多条件检索 + 分词高相关性 + category/brand 聚合
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private static final String INDEX = "mall_products";

    private final ElasticsearchClient es;

    @Value("${search.fulltext-fields:name,category,brand}")
    private String fulltextFields;

    private static final String INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "analysis": {
                  "analyzer": {
                    "ik_max_analyzer": { "type": "custom", "tokenizer": "ik_max_word" },
                    "ik_smart_analyzer": { "type": "custom", "tokenizer": "ik_smart" }
                  }
                }
              },
              "mappings": {
                "properties": {
                  "id":       { "type": "long" },
                  "name":     { "type": "text", "analyzer": "ik_max_analyzer", "search_analyzer": "ik_smart_analyzer", "fields": { "kw": { "type": "keyword" } } },
                  "category": { "type": "text", "analyzer": "ik_max_analyzer", "search_analyzer": "ik_smart_analyzer", "fields": { "kw": { "type": "keyword" } } },
                  "brand":    { "type": "text", "analyzer": "ik_max_analyzer", "search_analyzer": "ik_smart_analyzer", "fields": { "kw": { "type": "keyword" } } },
                  "price":    { "type": "scaled_float", "scaling_factor": 100 },
                  "stock":    { "type": "integer" }
                }
              }
            }""";

    /** 索引不存在则创建（幂等）。 */
    public void ensureIndex() throws Exception {
        boolean exists = es.indices().exists(e -> e.index(INDEX)).value();
        if (!exists) {
            es.indices().create(CreateIndexRequest.of(c -> c
                    .index(INDEX)
                    .withJson(new StringReader(INDEX_MAPPING))));
            log.info("ES index [{}] created with IK mapping", INDEX);
        }
    }

    /** MQ 事件 upsert（单条）。 */
    public void upsert(ProductChangedMessage m) throws Exception {
        es.index(i -> i.index(INDEX).id(String.valueOf(m.getId()))
                .document(toDoc(m)));
    }

    /** 全量重建（先按 DB 全量灌入；带 in-memory 缓冲的 bulk）。 */
    public int reindex(List<ProductChangedMessage> all) throws Exception {
        ensureIndex();
        BulkRequest.Builder br = new BulkRequest.Builder();
        int n = 0;
        for (ProductChangedMessage m : all) {
            br.operations(op -> op.index(idx -> idx.index(INDEX).id(String.valueOf(m.getId())).document(toDoc(m))));
            n++;
        }
        if (n > 0) {
            BulkResponse resp = es.bulk(br.build());
            if (resp.errors()) {
                log.error("reindex bulk has errors: {}", resp.items().stream().filter(i -> i.error() != null).count());
            }
        }
        log.info("reindexed {} docs into {}", n, INDEX);
        return n;
    }

    /**
     * 检索：多字段分词 match（name^3 权重最高 > brand^2 > category），range 过滤，排序。
     */
    public Map<String, Object> search(String q, String category, String brand,
                                      Double minPrice, Double maxPrice,
                                      int pageNum, int pageSize, String sort) throws Exception {
        Query query;
        if (q != null && !q.isBlank()) {
            List<String> fields = List.of(fulltextFields.split(","));
            query = Query.of(b -> b.bool(bf -> {
                bf.must(m -> m.multiMatch(mm -> mm.query(q)
                        .fields(fields.stream().map(f -> f.equals("name") ? "name^3" : f.equals("brand") ? "brand^2" : f).toList())));
                if (category != null && !category.isBlank()) {
                    bf.filter(f -> f.term(t -> t.field("category.kw").value(FieldValue.of(category))));
                }
                if (brand != null && !brand.isBlank()) {
                    bf.filter(f -> f.term(t -> t.field("brand.kw").value(FieldValue.of(brand))));
                }
                if (minPrice != null || maxPrice != null) {
                    bf.filter(f -> f.range(r -> {
                        if (minPrice != null) r.gte(co.elastic.clients.json.JsonData.of(minPrice));
                        if (maxPrice != null) r.lte(co.elastic.clients.json.JsonData.of(maxPrice));
                        return r;
                    }));
                }
                return bf;
            }));
        } else {
            List<Query> filters = new java.util.ArrayList<>();
            if (category != null && !category.isBlank()) {
                filters.add(Query.of(f -> f.term(t -> t.field("category.kw").value(FieldValue.of(category)))));
            }
            if (brand != null && !brand.isBlank()) {
                filters.add(Query.of(f -> f.term(t -> t.field("brand.kw").value(FieldValue.of(brand)))));
            }
            if (minPrice != null || maxPrice != null) {
                filters.add(Query.of(f -> f.range(r -> {
                    if (minPrice != null) r.gte(co.elastic.clients.json.JsonData.of(minPrice));
                    if (maxPrice != null) r.lte(co.elastic.clients.json.JsonData.of(maxPrice));
                    return r;
                })));
            }
            query = filters.isEmpty() ? Query.of(m -> m.matchAll(ma -> ma))
                    : Query.of(b -> b.bool(bf -> bf.filter(filters)));
        }

        SearchResponse<ProductDocument> resp = es.search(s -> {
            s.index(INDEX).query(query)
                    .from((pageNum - 1) * pageSize).size(pageSize);
            if ("price_asc".equals(sort)) {
                s.sort(so -> so.field(f -> f.field("price").order(SortOrder.Asc)));
            } else if ("price_desc".equals(sort)) {
                s.sort(so -> so.field(f -> f.field("price").order(SortOrder.Desc)));
            } else {
                s.sort(so -> so.score(sc -> sc.order(SortOrder.Desc)));
            }
            s.aggregations("categories", a -> a.terms(t -> t.field("category.kw").size(20)));
            s.aggregations("brands", a -> a.terms(t -> t.field("brand.kw").size(20)));
            return s;
        }, ProductDocument.class);

        long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
        List<ProductDocument> docs = resp.hits().hits().stream().map(Hit::source).toList();
        Map<String, Long> categories = new java.util.LinkedHashMap<>();
        resp.aggregations().get("categories").sterms().buckets().array()
                .forEach(b -> categories.put(b.key().stringValue(), b.docCount()));
        Map<String, Long> brands = new java.util.LinkedHashMap<>();
        resp.aggregations().get("brands").sterms().buckets().array()
                .forEach(b -> brands.put(b.key().stringValue(), b.docCount()));

        return Map.of(
                "total", total,
                "records", docs,
                "facets", Map.of("categories", categories, "brands", brands)
        );
    }

    private ProductDocument toDoc(ProductChangedMessage m) {
        return ProductDocument.builder()
                .id(m.getId()).name(m.getName()).category(m.getCategory())
                .brand(m.getBrand()).price(m.getPrice()).stock(m.getStock())
                .build();
    }
}
