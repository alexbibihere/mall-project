package com.mall.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 进程内 L1 缓存（多级缓存第一级）：5s 短窗 + 小容量，
 * 抗住同一热点 key 的瞬时重复读，过期后回落 L2(Redis)/DB。
 */
@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<Long, Object> localCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(5))
                .maximumSize(10_000)
                .build();
    }
}
