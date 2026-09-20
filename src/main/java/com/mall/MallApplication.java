package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 亿级流量商城 M1 单体版启动类。
 * M2 计划：拆分微服务 + RocketMQ 异步化；M3：分库分表 + 对账中心。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.mall.**.mapper")
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
