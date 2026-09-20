package com.mall.seckill.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀库存服务：Redis 分桶 + Lua 原子扣减。
 * - 分桶：热点库存拆 N 桶，哈希路由分散热点 key（对标大厂库存分桶方案）
 * - Lua：判限购 + 扣减 + 记用户 一次原子完成，杜绝查了再扣的竞态
 * - 口径：秒杀库存独立于日常库存（活动预热时从 DB 快照），两池隔离
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillStockService {

    private static final String BUCKET_PREFIX = "stock:seckill:";
    private static final String USERS_SUFFIX = ":users";

    private final StringRedisTemplate redis;

    @Value("${seckill.bucket-count}")
    private int bucketCount;

    private DefaultRedisScript<String> deductScript;

    @PostConstruct
    public void init() {
        deductScript = new DefaultRedisScript<>();
        deductScript.setLocation(new ClassPathResource("lua/seckill_deduct.lua"));
        deductScript.setResultType(String.class);
    }

    /** 活动预热：重置分桶（总量尽量均摊到各桶）并清空已购集合。 */
    public void warmup(Long productId, int totalStock) {
        String base = BUCKET_PREFIX + productId;
        int per = totalStock / bucketCount;
        int remainder = totalStock % bucketCount;
        for (int i = 0; i < bucketCount; i++) {
            int stock = per + (i < remainder ? 1 : 0);
            redis.opsForValue().set(base + ":b" + i, String.valueOf(stock));
        }
        redis.delete(base + USERS_SUFFIX);
        log.info("seckill warmup done, productId={}, total={}, buckets={}", productId, totalStock, bucketCount);
    }

    /**
     * 原子尝试扣减。
     *
     * @return 扣中的桶序号；-1=重复购买；-2=售罄
     */
    public int tryDeduct(Long productId, Long userId, int qty) {
        String base = BUCKET_PREFIX + productId;
        List<String> keys = new ArrayList<>(bucketCount + 1);
        for (int i = 0; i < bucketCount; i++) {
            keys.add(base + ":b" + i);
        }
        keys.add(base + USERS_SUFFIX);
        int routeHash = Long.hashCode(userId);
        String result = redis.execute(deductScript, keys,
                String.valueOf(qty), String.valueOf(userId), String.valueOf(Math.abs(routeHash)));
        if (result == null) {
            return -2;
        }
        if (result.startsWith("OK|")) {
            return Integer.parseInt(result.split("\\|")[1]);
        }
        return "DUP".equals(result) ? -1 : -2;
    }

    /** 消费失败回补：恢复桶库存并从已购集合移除（允许用户重试）。 */
    public void restore(Long productId, Long userId, int bucketIdx, int qty) {
        String base = BUCKET_PREFIX + productId;
        redis.opsForValue().increment(base + ":b" + bucketIdx, qty);
        redis.opsForSet().remove(base + USERS_SUFFIX, String.valueOf(userId));
    }

    /** 桶合计余量（对账用）。 */
    public long remainTotal(Long productId) {
        String base = BUCKET_PREFIX + productId;
        long sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            String v = redis.opsForValue().get(base + ":b" + i);
            sum += v == null ? 0 : Long.parseLong(v);
        }
        return sum;
    }

    public boolean purchased(Long productId, Long userId) {
        return Boolean.TRUE.equals(redis.opsForSet()
                .isMember(BUCKET_PREFIX + productId + USERS_SUFFIX, String.valueOf(userId)));
    }
}
