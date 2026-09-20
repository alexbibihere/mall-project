# 亿级流量商城 M1（单体版）

高并发商城项目第一期：单体跑通核心交易链路，为 M2（微服务化+MQ 异步）/ M3（分库分表+对账）/ M4（大促保障）打地基。

## 技术栈（M1）

| 项 | 选型 |
|---|---|
| JDK | 21（LTS） |
| 框架 | Spring Boot 3.5.x |
| ORM | MyBatis-Plus 3.5.12（spring-boot3-starter） |
| 数据库 | MySQL 8.0（本机 3306，库 `mall`，账号 root/root） |
| 缓存 | Redis 7（WSL Ubuntu-24.04，Windows 经 127.0.0.1:6379 直连） |
| 认证 | JWT（jjwt 0.12） |
| ID | 雪花算法（自实现，含时钟回拨等待） |

## 核心设计点（面试讲点）

- **防超卖基线**：`UPDATE product SET stock=stock-? WHERE id=? AND stock>=?` 乐观锁，影响行数=0 即库存不足
- **订单状态机 CAS**：`UPDATE orders SET status=#{to} WHERE order_no=? AND status=#{from}`——并发支付/取消只有一方生效，天然幂等
- **支付回调三重防资损**：支付单 CAS 幂等 → 金额校验 → 订单状态机二次 CAS；重复回调返回 duplicate=true
- **缓存旁路 + 空值防穿透**：详情读 Redis→DB→回填；不存在商品缓存 60s 空值
- **超时关单**：定时扫描 + Redis 分布式锁防多实例重复关（M2 换 RocketMQ 延迟消息）
- **快照设计**：订单固化地址/商品名/单价，防后续变更影响历史单

## 快速开始

```bash
# 1. 建库建表（幂等）
mysql -uroot -proot < sql/schema.sql

# 2. 启动 Redis（WSL，已装则跳过）
wsl -d Ubuntu-24.04 -u root -- service redis-server start

# 3. 编译打包
mvn.cmd clean package -DskipTests

# 4. 启动（8080 端口）
java -jar target/mall-monolith-1.0.0.jar
```

## 测试

```bash
# 全链路冒烟：注册→登录→地址→加购→下单→支付→重复回调幂等→购物车清理
python scripts/mall_bench.py smoke

# 并发下单基线压测（QPS/P99/错误分布/库存消耗核对）
python scripts/mall_bench.py bench --threads 50 --total 500 --product-id 1
```

mock 验证码固定 `123456`；mock 支付回调 `POST /api/payments/mock/notify/{paymentId}`。
