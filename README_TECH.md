# README_TECH · 技术详解总纲

> 商城项目所有技术的**一站式**文档：每一项技术 = 使用位置 → 核心代码 → 逻辑解析 → 面试话术（含简历对照）。
> 代码全部摘自真实源码，路径可直接查阅；配合 [docs/RESUME_TECH_DEEP_DIVE.md](docs/RESUME_TECH_DEEP_DIVE.md)（简历定制版）食用。

## 目录

- [0. 技术全景图](#0-技术全景图)
- [1. SpringCloud Alibaba（Nacos/Feign/Sentinel）](#1-springcloud-alibaba)
- [2. 分布式事务：AT 试点 → 本地消息表最终一致 ⭐](#2-分布式事务)
- [3. Redis：分桶 Lua / 多级缓存 / 幂等 / 限流 ⭐](#3-redis)
- [4. RocketMQ：消息不丢不重 + 堆积自愈](#4-rocketmq)
- [5. Elasticsearch + IK 搜索](#5-elasticsearch)
- [6. MySQL：乐观锁 / 状态机 CAS / MVCC](#6-mysql)
- [7. ShardingSphere 分库分表 + 基因法 ⭐](#7-分库分表与基因法)
- [8. Java 21 / Spring Boot 3 / MyBatis-Plus](#8-java-21--spring-boot-3)
- [9. 订单状态机（设计模式落地）](#9-订单状态机)
- [10. 网关鉴权与 CORS](#10-网关)
- [11. 对账中心与超时关单](#11-对账与关单)
- [12. 工程化：Docker/Git/Maven/测试](#12-工程化)
- [附A. 技术→代码速查表](#附a-代码速查表)
- [附B. 实测数据表](#附b-实测数据)

---

## 0. 技术全景图

```
前端 webapp/:8088 (原生JS SPA·9页面)
   │ CORS
   ▼
Gateway :9000 ── JWT校验·白名单·X-User-Id注入 ── lb://负载均衡
   │ Feign(服务名寻址 via Nacos)
   ▼
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│ user    │ product │ cart    │ order   │ seckill │ search  │
│ :8101   │ :8112   │ :8113   │ :8104   │ :8105   │ :8106   │
│ JWT/地址│ 多级缓存│ Redis   │ 状态机  │ Lua分桶 │ ES+IK   │
│         │ 虚拟线程│ Hash    │ 消息表  │ 限流幂等│ MQ同步  │
│         │         │         │ 分库分表│ 对账    │         │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
   │           │            │            │
 MySQL×2    Redis 7     RocketMQ 5    Elasticsearch
 (SS分片)   (6种用法)   (3 Topic)     8.11.4
```

---

## 1. SpringCloud Alibaba

**使用位置**：7 服务注册（Nacos `:8848`）· 网关路由（`application.yml`）· Feign 6 条链路 · 自研参数级限流

### 网关动态路由
```yaml
spring.cloud.gateway.routes:
  - id: product
    uri: lb://mall-product          # lb = 从 Nacos 拉实例列表做客户端负载均衡
    predicates: [ "Path=/api/products/**" ]
```

### Feign 声明式调用（mall-order-service/.../feign/ProductInternalClient.java）
```java
@FeignClient(name = "mall-product", path = "/internal/product")
public interface ProductInternalClient {
    @GetMapping("/{id}")
    Result<Map<String, Object>> product(@PathVariable Long id);
}
```

### Sentinel 参数级流控（mall-common/.../sentinel/ParamFlowRuleManager.java）
```java
@Before("@annotation(rateLimit)")                       // AOP 织入 @RateLimit 注解
public void checkParamFlow(JoinPoint jp, RateLimit rateLimit) {
    String key = "flow:" + resource + "__" + userId;    // 维度 = 资源×用户
    Long allowed = redis.execute(PARAM_FLOW, List.of(key),
            String.valueOf(now), String.valueOf(rateLimit.windowSeconds()),
            String.valueOf(rateLimit.limit()), member); // ZSET 滑窗 Lua
    if (allowed == null || allowed == 0L)
        throw new BizException(429, "请求过于频繁，请稍后再试");
}
```

**逻辑**：Nacos 心跳注册（30s，挂掉摘除）→ 网关按服务名负载均衡（换端口零改动，product 8102→8112 实证）→ Feign 动态代理发 HTTP，`X-User-Id` 由网关注入透传，下游 `UserContext` 读取。限流 = `sentinel_param_flow.lua` 的 ZADD→ZREM过期→ZCARD计数，口径对齐 Sentinel ParamFlowRule。

**面试话术**：*"能源项目 Nacos/Feign 我按团队规范用；为吃透原理自己完整搭了一遍 7 服务注册+动态路由。Sentinel 大多数人只会加注解，我把参数级流控的滑窗统计用 Redis Lua 复刻——所以能讲清 ParamFlow 底层就是『按参数的滑动窗口』。"*

---

## 2. 分布式事务 ⭐

**使用位置**：`OrderService.create`（下单）· `order_stock_task`（本地消息表）· `StockDeductTaskConsumer`（消费）· `StockTaskCompensator`（补偿）

### 为什么弃用 Seata AT（数据驱动决策）
| 维度 | Seata AT（M2.2 实测） | 本地消息表（M3.1 现行） |
|---|---|---|
| 下单吞吐 | **22.5 QPS**（-86.6%）| 链路解耦，异步扣减 |
| 一致性 | 强一致（2PC+全局锁）| 最终一致（秒级收敛）|
| 代价 | 每分支写 undo_log 前后镜像 + 与 TC 3 次往返 | 一张任务表 |

### 下单侧（OrderService.java:125-150）——本地事务打包「订单+任务」，提交后发 MQ
```java
// 5. 本地消息表：与订单同事务落库（订单成功 => 扣减任务必达）
OrderStockTask t = new OrderStockTask();
t.setOrderNo(order.getOrderNo()); t.setProductId(oi.getProductId());
t.setStatus(OrderStockTask.ST_INIT);        // INIT
stockTaskMapper.insert(t);

// 6. 事务提交后发 MQ（发送失败不回滚订单，补偿任务会重发）
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {
        rocketMQTemplate.syncSend(MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
                MessageBuilder.withPayload(t.getId()).build());
    }
});
```

### 消费侧（StockDeductTaskConsumer.java:48-70）——CAS 抢任务 = 天然幂等
```java
if (taskMapper.casGrab(taskId) == 0) return;   // INIT→PROCESSING 抢不到=已处理，重发不重复扣
Result<Boolean> r = productInternalClient.deduct(task.getProductId(), task.getQuantity());
if (r.getCode() == 0 && Boolean.TRUE.equals(r.getData())) {
    taskMapper.updateById(done);               // 成功 → DONE
}
// 库存不足：等同单兄弟任务终态 → 只回补已 DONE 的行（防超补）→ 状态机关单 → FAILED 终态
```

**四层可靠性**：①任务表与订单同事务（必达）→ ②afterCommit 发 MQ（时序正确）→ ③补偿器每 10s 重扫 INIT 重发 → ④对账任务每 5 分钟闭环校验。

**面试话术**：*"简历上的方案我不止会用：AT 真接入过，然后自己测出 -86.6% 的代价把它换掉了。库存不需要强一致，换成本地消息表：任务表和订单同一事务保证『订单成功扣减必达』，消费端 CAS 抢任务天然幂等，补偿+对账双兜底。这个选型是压测数据驱动的。"*（TCC/XA/Saga 能讲原理+空回滚/悬挂，诚实说明未用的原因：库存场景无强一致必要。）

---

## 3. Redis ⭐

**使用位置**：6 种用法分布在 4 个服务——多级缓存 L2（product）/ Hash 购物车（cart）/ 分桶库存+已购集合（seckill）/ 幂等令牌 setnx（common）/ ZSET 限流（common）/ Redisson 锁（order）

### 3.1 Lua 原子扣减（lua/seckill_deduct.lua）——防超卖核心
```lua
if redis.call('SISMEMBER', userSet, userId) == 1 then
  return 'DUP'                            -- 一人一单（Set 判重）
end
local start = routeHash % n               -- userId 哈希路由起始桶，防热点集中
for i = 0, n - 1 do
  local idx = (start + i) % n
  local remain = tonumber(redis.call('GET', KEYS[idx+1]) or '0')
  if remain >= qty then
    redis.call('DECRBY', KEYS[idx+1], qty)
    redis.call('SADD', userSet, userId)
    return 'OK|'..idx..'|'..(remain-qty)  -- 扣中的桶号带回
  end
end
return 'SOLDOUT'                          -- 全桶售罄才拒绝（桶间自动顺延）
```
Java 调用（SeckillStockService.tryDeduct）：N 个桶 key + users 集合整体作为 KEYS 传入——**Redis 单线程执行脚本期间不插其他命令**，"查了再扣"竞态物理消失。

### 3.2 三件套的项目对应
| 问题 | 项目方案 |
|---|---|
| 穿透 | 参数校验 + Feign 存在性校验 + 空值短 TTL 缓存 |
| 击穿 | Caffeine L1 进程内兜底 + TTL 随机抖动错峰重建 |
| 雪崩 | 多级缓存架构（L1 命中时 L2/DB 零压力）|

**实测**：50 并发秒杀 89.1 TPS，受理=Redis=DB 三方一致，**零超卖**。

**面试话术**：*"三件套都有真实对应：穿透是校验+空值缓存、击穿靠 L1 兜底+TTL 抖动、雪崩靠多级架构本身。最硬的是超卖——Lua 把判限购/扣减/记录做成一次原子操作，库存拆 10 桶哈希路由抗热点 key，压测零超卖且三方数字完全一致。"*

---

## 4. RocketMQ

**使用位置**：3 个 Topic——`ORDER_STOCK_DEDUCT`（下单扣减）· `SECKILL_ORDER_CREATE`（秒杀落库）· `PRODUCT_CHANGED`（ES 同步）

### 消息不丢（三段式）
```java
// 生产段：同步发送等 Broker 确认；失败仅 warn（任务表还在 INIT，补偿器重发）
rocketMQTemplate.syncSend(topic, MessageBuilder.withPayload(t.getId()).build());
// 存储段：Broker 刷盘/复制策略（生产配置）
// 消费段：异常→自动重试16次→死信队列→对账任务强制终态，不静默丢失
```

### 消息不重（业务幂等三种）
| 场景 | 幂等实现 |
|---|---|
| 库存扣减 | DB CAS 抢任务（casGrab）|
| 支付回调 | 唯一索引 + CAS 状态机 |
| 秒杀防重放 | Redis setnx 一次性令牌（IdempotentAspect）|

### 堆积自愈（实证）
补偿器扫描 `LIMIT` 批量重发 + 消费组水平扩容；**MQ 杀掉再启动，滞留任务 10 秒内被重发消费**。

### afterCommit 时序（ProductEventPublisher）
```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {      // 事务提交后才发——消费者读到的一定是已提交数据
        rocketMQTemplate.syncSend(MqTopics.PRODUCT_CHANGED_TOPIC, msg);
    }
});
```

**面试话术**：*"不丢按三段防：发送段 syncSend+本地消息表重发，存储段刷盘配置，消费段重试+死信+对账。不重不靠 MQ（它只保证 at-least-once），靠业务幂等——我实现了 CAS/唯一索引/setnx 三种。堆积自愈实证过：MQ 停机重启，滞留任务 10 秒收敛。"*

---

## 5. Elasticsearch

**使用位置**：`mall-search-service`（:8106）——`ProductSearchService.java` + `SearchIndexInitRunner`（启动全量对账）

### IK 双分析器 mapping（行 41-63）
```json
"name": { "type": "text",
  "analyzer": "ik_max_analyzer",          // 建索引：细粒度切词 → 提高召回
  "search_analyzer": "ik_smart_analyzer", // 检索：粗粒度切词 → 保证准确
  "fields": { "kw": { "type": "keyword" } } }  // kw 子字段：精确过滤/聚合用
```

### 检索（行 110-160）：multiMatch 加权 + filter 不算分 + 聚合一次带回
```java
bf.must(m -> m.multiMatch(mm -> mm.query(q)
        .fields(List.of("name^3", "brand^2", "category"))));  // 商品名权重3倍
bf.filter(f -> f.term(t -> t.field("category.kw").value(category))); // filter:不算分+bitset缓存
s.aggregations("categories", a -> a.terms(t -> t.field("category.kw").size(20)));
```

### DB→ES 最终一致（三层）
1. product 库存变更 → 事务提交后发 `PRODUCT_CHANGED`（快照全量字段）
2. search 消费 → 按商品 id `_id` upsert（天然幂等）
3. 启动时 `SearchIndexInitRunner` 全量 reindex 对账（**实测：100 商品灌入后 indexed:100，3 秒对齐**）

**面试话术**：*"ES 闭环：IK 双分析器各司其职，multiMatch 按业务加权（商品名^3），筛选走 filter 不算分吃缓存。一致性靠『事务后 MQ + upsert + 全量 reindex』三层，100 文档对账 3 秒。"*

---

## 6. MySQL

**使用位置**：乐观锁扣减（ProductMapper）· CAS 状态流转（OrderMapper.casStatus）· MVCC 场景化应用

```sql
-- 乐观锁：影响行数=0 即库存不足，无锁化扣减
UPDATE product SET stock = stock - #{qty} WHERE id = #{id} AND stock >= #{qty};

-- 状态机 CAS：并发下状态不跳变
UPDATE orders SET status = #{to} WHERE order_no = #{orderNo} AND status = #{from};
```

**MVCC 落到场景**：下单读价是普通 SELECT（快照读不加锁），扣库存是 UPDATE（当前读+行锁）——读写互不阻塞；价格以服务端为权威（Feign 回读），客户端传入金额一律不采信。

**面试话术**：*"RR 隔离级别下，读价走 MVCC 快照读、扣减走当前读行锁，两者靠『服务端价格权威+乐观锁』保证不超卖——MVCC 不是背概念，是下单链路里真实工作的机制。"*

---

## 7. 分库分表与基因法 ⭐

**使用位置**：`ShardingSphereConfig.java`（编程式配置）· `SnowflakeIdGenerator.nextOrderNo(userId)`（基因注入）

### 分片规则（2 库 × 2 表）
```java
// orders/order_item：user_id 路由（库表同规则）
orders.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "db-mod"));
orders.setTableShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "tbl-mod-orders"));
// 行表达式：ds${user_id % 2} / orders_${user_id % 2}

// order_status_log：订单号基因路由（末位 = user_id%10 → %2 与 user_id 恒等）
"ds${new Integer(order_no.substring(order_no.length()-1)) % 2}"
```

### 基因注入（SnowflakeIdGenerator）
```java
/** M3.2 基因法：订单号末位 = user_id % 10 */
public String nextOrderNo(long userId) {
    long gene = Math.abs(userId % 10);
    return "SO" + nextId() + gene;
}
```

**为什么**：订单按 buyer 分片后，seller 维度查询要扫全片。把 `user_id%10` 嵌进订单号末位 → buyer 维度（user_id%2 路由）与订单号维度（末位%2 路由）**数学恒等同片**，一笔数据落点唯一。

**验证**：存量 2090 行迁移后，新单基因路由正确率 3/3；5.5.x 三个亲踩坑（坐标改名/snakeyaml 冲突→编程式 API/单表显式注册）都是真实素材。

**面试话术**：*"ShardingSphere 5.5.2 做了 2库×2表：订单按 user_id 路由，跨维度查询用基因法——订单号末位嵌 user_id%10，buyer 和订单号天然同片。落地时 2090 行存量平滑迁移，新单路由 100% 正确。YAML 引擎和 Boot 的 snakeyaml 2.x 冲突，我改成编程式 API 构建数据源，顺手趟平了 5.5.x 的三个版本坑。"*

---

## 8. Java 21 + Spring Boot 3

**使用位置**：全项目 JDK21 + Boot 3.5.6；`spring.threads.virtual.enabled: true`（虚拟线程）

```yaml
spring:
  threads:
    virtual:
      enabled: true   # Tomcat 用虚拟线程承载请求
```

**逻辑**：下单链路是同步阻塞代码（Feign+DB），虚拟线程在阻塞点自动让出 carrier 线程——不重构成 WebFlux 也能扛并发。**坑**：synchronized 内阻塞会 pin 住载体线程，所以分布式锁选 Redisson（ReentrantLock 语义）而非 synchronized。

**面试话术**：*"Java 21 用了虚拟线程和 record：下单是同步阻塞链路，虚拟线程让阻塞变廉价，比改响应式性价比高。我知道 pinned 问题——synchronized 里阻塞会钉住载体线程，这正是我选 Redisson 的原因之一。"*

---

## 9. 订单状态机

**使用位置**：`OrderStateMachine.java`——所有流转的唯一入口（支付回调/取消/超时关单/库存不足关单）

```java
/** 转移表：集中校验合法流转 */
public static boolean isAllowed(String from, String to) {
    if (ST_UNPAID.equals(from)) return ST_PAID.equals(to) || ST_CANCELLED.equals(to);
    if (ST_PAID.equals(from))   return ST_CANCELLED.equals(to);   // 退款预留
    return false;                                                  // CANCELLED 终态
}

/** CAS 流转 + 流水审计（同一事务） */
public boolean transit(String orderNo, String from, String to, String event, String operator) {
    if (!isAllowed(from, to)) throw BizException.of(409, "非法状态流转");
    if (orderMapper.casStatus(orderNo, from, to) == 0) return false;
    logMapper.insert(new OrderStatusLog(orderNo, from, to, event, operator, now));  // 只增不改
    return true;
}
```

**呼应简历**：能源项目「策略模式解耦 18 类状态更新」的同思想异形态——**把变化的维度收敛到独立单元，新增场景只加不改**。`order_status_log` 实测记录：`UNPAID→PAID by PAY_NOTIFY`、`UNPAID→CANCELLED by STOCK_INSUFFICIENT`。

---

## 10. 网关

**使用位置**：`AuthGlobalFilter.java` + `CorsConfig.java`

```java
// JWT 校验通过后：剥离客户端伪造头 → 注入可信身份
private static final List<String> OPEN_PREFIXES = List.of(
        "/api/auth", "/api/products", "/api/search", "/api/payments/mock/notify/");  // 白名单
Claims claims = Jwts.parser().verifyWith(key).build()
        .parseSignedClaims(auth.substring(7)).getPayload();
exchange.mutate().request(builder.header("X-User-Id", claims.getSubject()));  // 下游可信
```
```java
// CORS：SCG 必须 CorsWebFilter（yml 方式对网关不生效）
cfg.setAllowedOrigins(List.of("http://localhost:8088", ...));  // 前端 SPA 跨域
```

服务层豁免机制：`WebConfig` 读取 `mall.auth.exclude-patterns`（search 配 `/api/search` 免登录）——网关白名单与服务层豁免**双层对齐**。

---

## 11. 对账与关单

**使用位置**：`OrderReconcileTask`（订单闭环，每5分钟）· `StockReconcileTask`（库存 drift，活动口径）· `OrderTimeoutReaper`（Redisson 锁关单，每30s）

```java
// 对账三条校验：①CANCELLED单任务必须终态(自动置FAILED) ②UNPAID滞留INIT重发 ③PAID必须DONE(资损告警)
@Scheduled(fixedDelay = 300_000)
public void reconcile() { /* 扫描近 500 单 → 反查任务 → switch 三态处理 */ }

// 超时关单：Redisson 看门狗锁（30s 自动续期，多实例不重复关）
RLock lock = redisson.getLock("lock:order:close:" + orderNo);
if (lock.tryLock(0, TimeUnit.SECONDS)) { orderService.closeIfTimeout(orderNo); }
```

**实测**：`drift=0`（活动口径完美收敛）；对账第一次运行就抓到秒杀 item 漏 user_id 分片键的真 bug——**对账不是装饰，是抓过真问题的防线**。

---

## 12. 工程化

| 项 | 实践 |
|---|---|
| Docker Compose | 6 容器编排（Nacos/Redis/RocketMQ×2/ES/Seata），restart:always |
| Maven | 8 模块聚合；git-bash 下必须 `mvn.cmd` |
| Git | 10+ 里程碑提交，GitHub 全量开源 |
| 质量保障 | 16 步冒烟（每次重构先回归）+ 压测脚本 + 仿真数据脚本（100商品/50用户/320订单）|

**面试话术**：*"每个里程碑合并前 16 步冒烟先跑绿——Seata 换消息表、分库分表上线都是冒烟证明零破坏后才合入。工程纪律和架构能力一样重要。"*

---

## 附A. 代码速查表

| 技术 | 文件 |
|---|---|
| Lua 扣减 | mall-seckill-service/src/main/resources/lua/seckill_deduct.lua |
| 分桶/回补 | mall-seckill-service/.../service/SeckillStockService.java |
| 本地消息表下单 | mall-order-service/.../service/OrderService.java |
| CAS 消费 | mall-order-service/.../consumer/StockDeductTaskConsumer.java |
| 状态机 | mall-order-service/.../service/OrderStateMachine.java |
| 分片配置 | mall-order-service/.../config/ShardingSphereConfig.java |
| 基因法 | mall-common/.../SnowflakeIdGenerator.java |
| 限流 | mall-common/.../sentinel/ParamFlowRuleManager.java + sentinel_param_flow.lua |
| 幂等 | mall-common/.../aspect/IdempotentAspect.java |
| 超时关单 | mall-order-service/.../service/OrderTimeoutReaper.java |
| 对账 | .../reconcile/OrderReconcileTask.java · .../reconcile/StockReconcileTask.java |
| ES | mall-search-service/.../service/ProductSearchService.java |
| 网关 | mall-gateway/.../filter/AuthGlobalFilter.java · CorsConfig.java |
| MQ 发布 | mall-product-service/.../mq/ProductEventPublisher.java |

## 附B. 实测数据

| 指标 | 数值 |
|---|---|
| 缓存热读 | 3036.8 QPS（P99 47.6ms，0 失败）|
| 秒杀受理 | 89.1 TPS（P99 116.2ms，零超卖）|
| Seata AT 代价 | 下单 -86.6%（167.6→22.5 QPS）|
| 分片路由 | 新单 3/3 正确 · 存量 2090 行迁移 |
| 对账 | drift=0 |
| 冒烟 | 16/16 全绿 |
| ES 对账 | 100 文档 3 秒全量对齐 |

---

> **配套文档**：[简历定制版 RESUME_TECH_DEEP_DIVE.md](docs/RESUME_TECH_DEEP_DIVE.md) · [面试叙事 INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md) · [面试QA全景 TECH_INTERVIEW_QA.md](docs/TECH_INTERVIEW_QA.md) · [压测报告 BENCH_M2_REPORT.md](docs/BENCH_M2_REPORT.md)
