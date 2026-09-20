package com.mall.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.BizException;
import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mq.ProductEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;

/**
 * 商品服务：M1.5 多级缓存版 + M2.3 变更事件（MQ -> search 同步 ES）。
 * L1 Caffeine(进程内, 5s 短窗抗热点) -> L2 Redis(30min, 旁路缓存+空值防穿透) -> DB
 */
@Slf4j
@Service
public class ProductService {

    private static final String KEY_PREFIX = "product:info:";
    private static final String KEY_NULL = "\"NULL\""; // 空值缓存标记，防穿透
    private static final Duration TTL = Duration.ofMinutes(30);

    private final ProductMapper productMapper;
    private final StringRedisTemplate redis;
    private final Cache<Long, Object> localCache;
    private final ProductEventPublisher eventPublisher;

    public ProductService(ProductMapper productMapper,
                          StringRedisTemplate redis,
                          @Qualifier("localCache") Cache<Long, Object> localCache,
                          ProductEventPublisher eventPublisher) {
        this.productMapper = productMapper;
        this.redis = redis;
        this.localCache = localCache;
        this.eventPublisher = eventPublisher;
    }

    public Page<Product> page(long pageNum, long pageSize, String keyword) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Product::getName, keyword);
        }
        return productMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    /** 详情：多级缓存读链路。 */
    public Product detail(Long id) {
        // L1：进程内（命中即返回，纳秒级）
        Object hit = localCache.getIfPresent(id);
        if (hit instanceof Product p) {
            return p;
        }
        // L2：Redis 旁路缓存
        String key = KEY_PREFIX + id;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            if (KEY_NULL.equals(cached)) {
                throw BizException.of("商品不存在");
            }
            Product p = fromCache(cached);
            if (p != null) {
                localCache.put(id, p);
                return p;
            }
            // 解码失败视同缓存失效，走 DB
        }
        Product p = productMapper.selectById(id);
        if (p == null) {
            // 空值缓存短 TTL，挡住恶意 id 扫描
            redis.opsForValue().set(key, KEY_NULL, Duration.ofSeconds(60));
            throw BizException.of("商品不存在");
        }
        redis.opsForValue().set(key, toCache(p), TTL);
        localCache.put(id, p);
        return p;
    }

    /** 库存/信息变更后主动失效（Cache-Aside：以 DB 为准，删缓存而非改缓存）。 */
    public void evict(Long id) {
        localCache.invalidate(id);
        redis.delete(KEY_PREFIX + id);
    }

    /** M2.3: 库存/信息变更后的完整失效链：本地缓存 + Redis + 变更事件（ES 同步）。 */
    public void evictAndPublish(Product p) {
        evict(p.getId());
        eventPublisher.publishChanged(p);
    }

    // 简化序列化：M1 用「id|name|category|brand|price|stock」文本行编码，
    // 避免 M1 引入 JSON 序列化器的额外依赖；M2 换 RedisTemplate+Jackson
    private String toCache(Product p) {
        return p.getId() + "|" + p.getName() + "|" + p.getCategory() + "|"
                + p.getBrand() + "|" + p.getPrice().toPlainString() + "|" + p.getStock();
    }

    private Product fromCache(String s) {
        try {
            String[] f = s.split("\\|", -1);
            Product p = new Product();
            p.setId(Long.valueOf(f[0]));
            p.setName(f[1]);
            p.setCategory(f[2]);
            p.setBrand(f[3]);
            p.setPrice(new java.math.BigDecimal(f[4]));
            p.setStock(Integer.valueOf(f[5]));
            return p;
        } catch (Exception e) {
            log.warn("cache decode failed, fallback to db", e);
            return null;
        }
    }
}
