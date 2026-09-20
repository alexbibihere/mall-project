package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 商品服务：商品/库存/多级缓存 + 演示种子数据。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@MapperScan("com.mall.product.mapper")
@EnableDiscoveryClient
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
