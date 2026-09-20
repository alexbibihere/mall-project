package com.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务：注册/登录/地址簿。MapperScan 由 MyBatis-Plus starter 自动扫描 @Mapper 注解接口。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@EnableDiscoveryClient
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
