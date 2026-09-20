package com.mall.common;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 雪花 ID 生成器（订单号/支付单号）。
 * 结构：1bit 符号 + 41bit 时间戳 + 10bit 机器ID + 12bit 序列号。
 * M1 单机版 machineId 随机初始化；M3 集群化时改为 DB/ Zookeeper 分配并增加时钟回拨防护。
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1735689600000L; // 2025-01-01
    private static final long MACHINE_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long MACHINE_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_BITS;

    private final long machineId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        // 单机版：进程内随机 machineId，重启碰撞概率低，M3 再改注册中心分配
        this.machineId = new SecureRandom().nextInt(1 << MACHINE_BITS);
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨：小幅回拨直接等待追平（生产级实现还需告警+拒绝阈值）
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                while ((now = System.currentTimeMillis()) <= lastTimestamp) {
                    // 自旋等待下一毫秒
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT) | (machineId << MACHINE_SHIFT) | sequence;
    }

    public String nextOrderNo() {
        return "SO" + nextId();
    }

    /**
     * M3.2 基因法：订单号末位 = user_id % 10（基因）。
     * 分库分表按订单号末位路由 => buyer 维度与订单号维度天然同片，无需跨片查询。
     */
    public String nextOrderNo(long userId) {
        long gene = Math.abs(userId % 10);
        return "SO" + nextId() + gene;
    }
}
