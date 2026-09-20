package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务：订单/支付/秒杀消费者/超时关单。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@MapperScan("com.mall.**.mapper")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mall.order.feign")
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
