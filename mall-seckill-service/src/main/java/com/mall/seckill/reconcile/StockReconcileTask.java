package com.mall.seckill.reconcile;

import com.mall.common.Result;
import com.mall.seckill.feign.ProductClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * M3.3 对账中心 · 秒杀库存对账（每 5 分钟）。
 *
 * 口径：Redis 分桶余量之和（可售口径）vs DB 库存（真值）。
 * 原则：Redis 是预扣口径允许短暂滞后（MQ 在途），以 DB 为准收敛；
 * 差异仅告警记录（漂移修复需区分「MQ 在途」与「真丢失」，自动修复有超加风险，交人工/M3.4）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconcileTask {

    private final StringRedisTemplate redis;
    private final ProductClient productClient;

    @Value("${seckill.bucket-count:10}")
    private int bucketCount;

    /** 预热基线：活动商品 pid -> [活动库存, 预热时 DB 库存]。 */
    private record Baseline(int activityStock, int dbStockAtWarmup) {}
    private volatile Map<Long, Baseline> warmupBaseline = Map.of();

    public void recordBaseline(long productId, int activityStock) {
        int dbNow = -1;
        try {
            Result<Map<String, Object>> r = productClient.detail(productId);
            if (r.getCode() == 0 && r.getData() != null) {
                dbNow = Integer.parseInt(String.valueOf(r.getData().get("stock")));
            }
        } catch (Exception ignore) { }
        var m = new java.util.HashMap<>(warmupBaseline);
        m.put(productId, new Baseline(activityStock, dbNow));
        warmupBaseline = Map.copyOf(m);
    }

    @Scheduled(fixedDelay = 300_000)
    public void reconcile() {
        var baseline = warmupBaseline;
        if (baseline.isEmpty()) {
            return;
        }
        for (var e : baseline.entrySet()) {
            long pid = e.getKey();
            int dbStock = -1;
            try {
                Result<Map<String, Object>> r = productClient.detail(pid);
                if (r.getCode() == 0 && r.getData() != null) {
                    dbStock = Integer.parseInt(String.valueOf(r.getData().get("stock")));
                }
            } catch (Exception ex) {
                log.warn("[RECONCILE] product fetch failed pid={}", pid);
                continue;
            }

            long redisRemain = 0;
            for (int b = 0; b < bucketCount; b++) {
                String v = redis.opsForValue().get("stock:seckill:" + pid + ":b" + b);
                redisRemain += v == null ? 0 : Long.parseLong(v);
            }

            // 活动口径：本活动消耗 = 活动库存 - 分桶余量；DB 消耗 = 预热时DB库存 - 当前DB库存
            long consumedRedis = e.getValue().activityStock() - redisRemain;
            long consumedDb = e.getValue().dbStockAtWarmup() - dbStock;
            long drift = consumedRedis - consumedDb;         // >0 = Redis 扣多了（MQ 在途/丢失）

            // 容忍带：MQ 异步在途（秒杀受理峰值可到数百），漂移在 [0, 200] 视为在途正常
            if (drift < 0 || drift > 200) {
                log.warn("[RECONCILE-DRIFT] pid={} redisConsumed={} dbConsumed={} drift={}（超容忍带，需人工核查分桶key）",
                        pid, consumedRedis, consumedDb, drift);
            } else {
                log.info("[RECONCILE] pid={} ok: redisConsumed={} dbConsumed={} drift={}（在途容忍内）",
                        pid, consumedRedis, consumedDb, drift);
            }
        }
    }
}
