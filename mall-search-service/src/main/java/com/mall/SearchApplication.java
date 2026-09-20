package com.mall;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * 搜索服务（M2.3）：ES 8 商品检索/聚合 + MQ 增量同步 + 全量对账。
 * 无 DB 依赖——数据源是 mall-product（MQ 事件 + reindex Feign 拉全量）。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mall")
@Import(RocketMQAutoConfiguration.class)
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
