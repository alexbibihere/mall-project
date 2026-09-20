package com.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 购物车服务：Redis Hash 车 + Feign 调商品服务补价格。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mall.cart.feign")
public class CartApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartApplication.class, args);
    }
}
