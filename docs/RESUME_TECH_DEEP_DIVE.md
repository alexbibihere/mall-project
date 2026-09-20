# 简历技术 × 商城项目 对照详解

> 每一节结构：**简历原文 → 项目哪里用 → 核心代码+解析 → 面试话术**
> 所有代码均为 mall-project 真实源码摘录，路径可直接展示。

---

## 一、SpringCloud Alibaba（Nacos / Feign / Sentinel）

### 1.1 简历原文
> "熟悉 SpringCloud Alibaba 常用组件的使用"
> 能源项目涉及技术：SpringCloud（Nacos/Feign/Sentinel）

### 1.2 项目哪里用
| 组件 | 落点 |
|---|---|
| Nacos 注册中心 | 7 个服务全部注册（mall-user/product/cart/order/seckill/search/gateway）|
| Nacos 动态路由 | `mall-gateway/src/main/resources/application.yml`：`uri: lb://mall-product` |
| OpenFeign | `mall-order-service/.../feign/ProductInternalClient.java` 等 6 条调用链 |
| Sentinel 语义限流 | `mall-common/.../sentinel/ParamFlowRuleManager.java`（参数级流控托管实现）|

### 1.3 核心代码
**Feign 声明式调用**（OrderService.java:82）：
```java
// 只声明接口，HTTP 细节全部由框架代理
@FeignClient(name = "mall-product", path = "/internal/product")
public interface ProductInternalClient {
    @GetMapping("/{id}")
    Result<Map<String, Object>> product(@PathVariable Long id);
}
```

**Sentinel 参数级流控**（ParamFlowRuleManager.java，@Aspect + Redis Lua 滑窗）：
```java
@Before("@annotation(rateLimit)")
public void checkParamFlow(JoinPoint jp, RateLimit rateLimit) {
    Long userId = UserContext.get();
    String key = "flow:" + resource + "__" + userId;   // 资源=类.方法，参数=userId
    Long allowed = redis.execute(PARAM_FLOW, List.of(key),
            String.valueOf(now), String.valueOf(rateLimit.windowSeconds()),
            String.valueOf(rateLimit.limit()), member);
    if (allowed == null || allowed == 0L)
        throw new BizException(429, "请求过于频繁");
}
```

### 1.4 逻辑解析
- **Nacos**：服务启动向 Nacos 注册 `服务名→IP:端口`，30s 心跳保活，挂掉自动摘除。网关路由写 `lb://服务名`，从 Nacos 拉列表做客户端负载均衡——**服务换端口/扩容，调用方零配置改动**（项目中 product 从 8102→8112 实证）。
- **Feign**：接口 + 注解 → 动态代理生成 HTTP 客户端，按服务名经 LoadBalancer 寻址；配合网关注入的 `X-User-Id` 头做用户身份透传。
- **限流**：`sentinel_param_flow.lua` 在 Redis 上执行 ZSET 滑窗（ZADD 时间戳 → ZREM 过期 → ZCARD 计数 → 超限拒绝），对齐 Sentinel ParamFlowRule「参数维度滑窗」口径，秒杀接口限「同一用户 5 次/秒」。

### 1.5 面试话术
> "能源项目里 Nacos/Feign 是团队现成设施，我按规范使用；为了吃透原理，我自己完整搭了一套：7 个服务全部 Nacos 注册、网关 `lb://` 动态路由、Feign 服务名调用。Sentinel 网上大多是引依赖加注解，我把它参数级流控的语义用 Redis Lua 滑窗自己实现了一遍——所以我能讲清 ParamFlowRule 底层就是『按参数统计的滑动窗口』，而不只是会加注解。"

---

## 二、分布式事务（AT / 可靠消息最终一致性）⭐ 简历与项目最强绑定点

### 2.1 简历原文
> "掌握分布式事务解决方案 XA、AT、TCC、Saga、**可靠消息最终一致性**的原理以及使用场景"

### 2.2 项目哪里用
**两个都真做过**：
- AT：M2.2 用 Seata 试点（`@GlobalTransactional`，下单同步扣库存）
- 可靠消息最终一致性：M3.1 **用实测数据把 AT 换掉了**——`OrderService.create` + `order_stock_task` 本地消息表 + `StockDeductTaskConsumer`

### 2.3 核心代码
**下单侧：本地事务 = 校验+订单落库+任务表落库，事务提交后才发 MQ**（OrderService.java:125-150）：
```java
// 5. 本地消息表：与订单同事务落库（订单成功 => 扣减任务必达）
OrderStockTask t = new OrderStockTask();
t.setOrderNo(order.getOrderNo());
t.setProductId(oi.getProductId());
t.setStatus(OrderStockTask.ST_INIT);
stockTaskMapper.insert(t);

// 6. 事务提交后发 MQ（发送失败不回滚订单：补偿任务重扫 INIT 重发，消息不丢）
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {
        rocketMQTemplate.syncSend(MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
                MessageBuilder.withPayload(t.getId()).build());
    }
});
```

**消费侧：CAS 抢任务 = 天然幂等**（StockDeductTaskConsumer.java:48-70）：
```java
// 幂等核心：CAS 抢任务（INIT/FAILED -> PROCESSING），抢不到说明已被处理
if (taskMapper.casGrab(taskId) == 0) { return; }

// 真扣减（product 乐观锁兜底）
Result<Boolean> r = productInternalClient.deduct(task.getProductId(), task.getQuantity());
if (r.getCode() == 0 && Boolean.TRUE.equals(r.getData())) {
    taskMapper.updateById(new OrderStockTask(taskId, ST_DONE));   // 成功 DONE
}
// 库存不足：等兄弟任务终态 → 只回补已 DONE 行（防超补）→ 状态机关单
```

### 2.4 逻辑解析（为什么换掉 AT）
| 维度 | Seata AT | 本地消息表 |
|---|---|---|
| 下单吞吐 | **22.5 QPS**（实测）| 异步化后链路解耦 |
| 一致性 | 强一致（2PC+全局锁）| 最终一致（秒级）|
| 侵入性 | 每分支 undo_log 前后镜像 | 一张任务表 |
| 适用 | 资金类强一致场景 | 库存扣减可异步场景 |

四层可靠性：①任务表与订单同事务（订单成功任务必达）→ ②afterCommit 发 MQ → ③发送失败/消费失败由 `StockTaskCompensator` 每 10s 重扫 INIT 重发 → ④`OrderReconcileTask` 每 5 分钟对账兜底。CAS 抢任务保证重发不重复扣。

### 2.5 面试话术（杀手锏）
> "简历上写的几个方案我不止会用——AT 我真接入过，然后**自己测出它的代价把它换掉了**：AT 模式下下单 QPS 从 167 掉到 22，因为每个分支要写 undo_log 前后镜像、分支注册和二阶段要跟 TC 三次往返。库存扣减不需要强一致，所以我换成可靠消息最终一致性：任务表和订单同一事务落库保证『订单成功则扣减必达』，事务提交后再发 MQ，消费端 CAS 抢任务天然幂等，补偿+对账双兜底。这个选型是数据驱动的，不是背来的。"
> （若追问 TCC/XA/Saga：能讲 Try-Confirm-Cancel 的空回滚/悬挂问题、XA 的锁粒度、Saga 的逆向补偿——诚实说明项目未用，理由是库存场景无此必要。）

---

## 三、Redis（穿透/击穿/雪崩 + 数据类型 + Lua）

### 3.1 简历原文
> "熟悉 Redis……理解 Redis 数据类型、持久化机制、过期删除与内存淘汰策略，能熟练解决缓存穿透、击穿、雪崩等问题"

### 3.2 项目哪里用
| 场景 | 类型/技术 | 落点 |
|---|---|---|
| 商品多级缓存 | String（L2）+ Caffeine（L1）| `ProductService`（Caffeine → Redis → DB 漏斗）|
| 购物车 | Hash（`cart:{uid}` field=pid, value=`qty\|checked`）| `CartService` |
| 秒杀库存分桶 | String ×N 桶 + Set（已购集合）| `SeckillStockService` |
| 幂等令牌 | String setnx + TTL | `IdempotentAspect` |
| 限流滑窗 | ZSET | `ParamFlowRuleManager` |
| 分布式锁 | Redisson 看门狗 | `OrderTimeoutReaper` |

### 3.3 核心代码
**Lua 原子扣减（判限购+扣减+记录一次完成）** `mall-seckill-service/src/main/resources/lua/seckill_deduct.lua`：
```lua
if redis.call('SISMEMBER', userSet, userId) == 1 then
  return 'DUP'                       -- 一人一单
end
local start = routeHash % n           -- 哈希路由起始桶，分散热点
for i = 0, n - 1 do
  local idx = (start + i) % n
  local remain = tonumber(redis.call('GET', KEYS[idx + 1]) or '0')
  if remain >= qty then
    redis.call('DECRBY', KEYS[idx + 1], qty)
    redis.call('SADD', userSet, userId)
    return 'OK|' .. idx .. '|' .. (remain - qty)
  end
end
return 'SOLDOUT'                      -- 全桶售罄才拒绝
```
Java 侧调用（SeckillStockService.tryDeduct）：把 N 个桶 key + users 集合作为 KEYS 整体传给脚本，**Redis 单线程执行期间不会插入其他命令**——"查了再扣"的竞态在物理上不存在。

### 3.4 逻辑解析（三件套在项目中的真实对应）
- **穿透**：商品详情接口先过参数校验 + Feign 存在性校验，DB 查不到时缓存空值短 TTL，防恶意 ID 打库。
- **击穿**：热点商品用 Caffeine L1 兜底（进程内无网络开销），Redis 层过期时间加随机抖动，重建时天然错峰。
- **雪崩**：缓存 TTL 随机化 + 多级架构本身抗雪崩（L1 命中时 L2/DB 压力为 0）。
- **分桶**：单 key 库存在大促下是热点 key（单核瓶颈），拆 10 桶哈希路由把写压力摊到 10 个 key；全桶售罄才 SOLDOUT。

### 3.5 面试话术
> "三件套我不止能背方案：穿透在项目里是『参数校验+空值缓存』，击穿靠 L1 Caffeine 进程内兜底+TTL 随机抖动，雪崩靠多级缓存架构本身。最值得讲的是超卖：我用 Lua 把『判限购、扣减、记用户』做成一次原子操作，库存拆 10 桶哈希路由防热点 key——压测 50 并发抢购，受理 89 TPS，Redis/DB 消耗完全一致，零超卖。"

---

## 四、RocketMQ（可靠性 / 堆积 / 丢失）

### 4.1 简历原文
> "熟练掌握 RocketMQ……掌握消息堆积、消息丢失的解决方案"

### 4.2 项目哪里用
| Topic | 用途 | 生产者/消费者 |
|---|---|---|
| ORDER_STOCK_DEDUCT | 下单异步扣库存 | OrderService → StockDeductTaskConsumer |
| SECKILL_ORDER_CREATE | 秒杀异步落库 | SeckillController → OrderCreateConsumer |
| PRODUCT_CHANGED | 库存变更→ES 同步 | ProductEventPublisher → SearchConsumer |

### 4.3 核心代码
**消息不丢的发送侧**（OrderService.java，见第二节引文）：
```java
syncSend(...);                          // 同步发送，等 Broker 确认
// 发送失败仅 warn 日志，不抛异常不回滚——因为任务表还在 INIT
```

**消费侧死信兜底**（StockDeductTaskConsumer）：处理失败抛异常 → RocketMQ 自动重试 16 次 → 进死信队列 → `OrderReconcileTask` 对账扫描强制终态，消息不静默丢失。

**事务外发的时序正确性**（ProductEventPublisher）：
```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {   // 事务提交后才发
        rocketMQTemplate.syncSend(MqTopics.PRODUCT_CHANGED_TOPIC, msg);
    }
});
```

### 4.4 逻辑解析
- **不丢**：生产侧 syncSend + 任务表兜底重发；Broker 侧同步刷盘/主从（生产配置）；消费侧重试+死信+对账。
- **不重**：MQ 只保证 at-least-once，去重在业务侧——CAS 抢任务 / 唯一索引 / setnx 令牌三种幂等。
- **堆积**：补偿器扫描是批量+限量（LIMIT），消费组可水平扩容；对账任务是漏斗最后一层，堆积再久最终也能收敛。
- **afterCommit 的意义**：事务未提交就发消息，消费者可能读到旧数据（库存还没落库）；提交后发保证消费时订单一定可见。

### 4.5 面试话术
> "消息丢失我按三段防：发送段同步发送+本地消息表重发；存储段 Broker 刷盘配置；消费段手动 ACK+重试+死信。消息重复不靠 MQ 靠业务幂等，我项目里三种都有：库存扣减用数据库 CAS 抢任务、支付回调用唯一索引、秒杀用 Redis setnx 令牌。堆积的自愈我用补偿扫描实证过：把 MQ 杀掉再启动，滞留任务 10 秒内被补偿器重发消费。"

---

## 五、Elasticsearch

### 5.1 简历原文
> "熟悉……ES 等非关系型数据库的使用"（供应链安全平台项目涉及技术：Elasticsearch）

### 5.2 项目哪里用
`mall-search-service`（:8106）：商品搜索全链路——IK 索引 mapping、MQ 增量同步、multiMatch 检索、terms 聚合、reindex 对账。

### 5.3 核心代码（ProductSearchService.java）
```java
// 双分析器：建索引用 ik_max_word（细粒度切词提高召回），检索用 ik_smart（粗粒度保准确）
"name": { "type": "text",
          "analyzer": "ik_max_analyzer",
          "search_analyzer": "ik_smart_analyzer",
          "fields": { "kw": { "type": "keyword" } } }   // kw 子字段供精确过滤/聚合

// 检索：multiMatch 加权 + filter 过滤 + 聚合一次请求带回
bf.must(m -> m.multiMatch(mm -> mm.query(q)
        .fields(List.of("name^3", "brand^2", "category"))));   // 商品名权重最高
bf.filter(f -> f.term(t -> t.field("category.kw").value(category)));  // filter 不算分、有缓存
s.aggregations("categories", a -> a.terms(t -> t.field("category.kw").size(20)));
```

### 5.4 逻辑解析
- **DB↔ES 一致性**：MySQL 是权威，product 库存变更后发 `PRODUCT_CHANGED`（事务提交后），search 消费 upsert ES（按商品 id 做 `_id` upsert，幂等）；启动时 `SearchIndexInitRunner` 全量 reindex 对账兜底（实测灌 100 商品后 `indexed:100` 对齐）。
- **query vs filter**：关键词走 must（算相关性得分），筛选条件走 filter（不算分、bitset 缓存）——这是 ES 性能调优的基本功。

### 5.5 面试话术
> "ES 我做过完整闭环：IK 双分析器——建索引 max_word 细切保召回、检索 smart 粗切保准确；检索是 multiMatch 按业务加权（商品名 3 倍权重），筛选走 filter 不算分。同步用『事务提交后发 MQ + 消费 upsert + 启动全量 reindex』三层保最终一致，100 商品全量对账 3 秒内完成。"

---

## 六、MySQL（MVCC / 索引 / 乐观锁 + 分库分表）

### 6.1 简历原文
> "熟悉 MySQL……理解 MVCC 机制、事务隔离级别、索引等核心原理，有 SQL 优化经验"

### 6.2 项目哪里用
- 乐观锁扣库存：`ProductMapper.deductStock`
- 状态机 CAS：`OrderMapper.casStatus`
- **ShardingSphere 5.5.2 分库分表 + 基因法**：`mall-order-service/.../config/ShardingSphereConfig.java`

### 6.3 核心代码
**乐观锁**（无锁化扣减的 SQL 层实现）：
```sql
UPDATE product SET stock = stock - #{qty}
WHERE id = #{id} AND stock >= #{qty}     -- 影响行数=0 即库存不足
```

**状态机 CAS**（并发下状态不跳变）：
```java
int affected = orderMapper.casStatus(orderNo, from, to);
// UPDATE orders SET status=#{to} WHERE order_no=#{no} AND status=#{from}
// affected==0 → 状态已被并发修改，按幂等/冲突处理
```

**分库分表（编程式配置，绕开 YAML 依赖冲突）** ShardingSphereConfig.java:
```java
// 库表：ds0=mall, ds1=mall_shard1；orders/order_item 各 _0/_1
orders.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "db-mod"));
orders.setTableShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "tbl-mod-orders"));
// 行表达式：ds${user_id % 2} / orders_${user_id % 2}

// 基因法：订单号末位 = user_id % 10，流水表按订单号基因路由，与 user_id 恒等同片
"ds${new Integer(order_no.substring(order_no.length()-1)) % 2}"
```
基因注入（SnowflakeIdGenerator.nextOrderNo）：
```java
public String nextOrderNo(long userId) {
    long gene = Math.abs(userId % 10);
    return "SO" + nextId() + gene;    // 订单号自带 buyer 基因
}
```

### 6.4 逻辑解析
- **为什么基因法**：订单表按 buyer_id 分片后，"商家查订单"（seller 维度）会跨全部片。把 `user_id%10` 基因嵌进订单号，则 buyer 维度（user_id 路由）与订单号维度（末位路由）**数学上恒等同片**（user_id%10 决定末位，两者 mod 2 结果一致），一笔数据落点唯一。
- **MVCC 关联**：下单读商品价是普通 SELECT（MVCC 快照读不加锁），扣减是 UPDATE（当前读+行锁）——读写不互相阻塞，这是 RR 隔离级别的实际应用。
- **分表索引**：每张分表独立 B+ 树，分片键 user_id 前置进索引，避免跨片查询。

### 6.5 面试话术
> "分库分表我用 ShardingSphere 5.5.2 做了 2 库×2 表：订单按 user_id 路由；跨维度查询用基因法解决——订单号末位嵌 user_id%10，让 buyer 维度和订单号维度天然同片，流水表按订单号基因路由跟主单同库。落地时验证了 2090 行存量迁移后新单路由 100% 正确。MVCC 我能落到场景：下单读价是快照读，扣库存是当前读加行锁，两者靠『服务端价格权威+乐观锁』保证不超卖。"
> （若被问 ShardingSphere 坑：5.5.x 改名 shardingsphere-jdbc、YAML 引擎与 Boot snakeyaml 2.x 冲突→改编程式 API、单表必须显式注册——三个都是亲踩的真坑。）

---

## 七、Java 21 + Spring Boot 3 + MyBatis-Plus

### 7.1 简历原文
> 能源项目涉及技术：**Java 21、Spring Boot 3**、SpringCloud、MyBatis-Plus

### 7.2 项目哪里用
全项目 JDK21 + Boot 3.5.6；`application.yml` 开 `spring.threads.virtual.enabled: true`；持久层 MyBatis + MyBatis-Plus（LambdaQueryWrapper / Page 分页在 OrderService、OrderReconcileTask 大量使用）。

### 7.3 核心代码
```yaml
spring:
  threads:
    virtual:
      enabled: true   # Tomcat 用虚拟线程承载请求
```

### 7.4 逻辑解析
- 下单链路是**同步阻塞式**（Feign 调用+DB 写），虚拟线程让"阻塞"变廉价： carrier 线程在阻塞点自动让出，平台线程数不再限制并发——这也是敢把链路做成简单同步代码的底气（对比 WebFlux 响应式的复杂度）。
- 时钟回拨防护（SnowflakeIdGenerator）：`if (now < lastTimestamp) now = lastTimestamp;` 小幅回拨直接追平，序列号溢出自旋等下一毫秒。

### 7.5 面试话术
> "Java 21 我用了两个特性：虚拟线程——下单链路是同步阻塞代码（Feign+DB），虚拟线程让阻塞不占平台线程，比改响应式代码的性价比高得多；还有 record 类型做不可变 DTO。JVM 层面我理解虚拟线程的 carrier 机制：pinned 场景（synchronized 块内阻塞）会钉住载体线程，这是我选 ReentrantLock/Redisson 而不是 synchronized 做长阻塞的原因之一。"

---

## 八、设计模式（呼应简历"策略模式解耦 18 类状态更新"）

### 8.1 简历原文
> "**策略模式**解耦 18 类业务状态更新……将各类状态更新拆成独立处理类，互相隔离，新增业务不改动原有代码即可扩展"

### 8.2 项目哪里用
**订单状态机**（`OrderStateMachine.java`）：状态模式思想 + CAS + 审计流水；消费端三态闭环 switch（OrderReconcileTask）。

### 8.3 核心代码
```java
/** 合法性校验：from -> to 是否允许（转移表收敛在一处） */
public static boolean isAllowed(String from, String to) {
    if (ST_UNPAID.equals(from)) return ST_PAID.equals(to) || ST_CANCELLED.equals(to);
    if (ST_PAID.equals(from))   return ST_CANCELLED.equals(to);   // 退款闭环预留
    return false;                                                  // CANCELLED 终态
}

/** CAS 流转 + 流水记录（同一事务） */
public boolean transit(String orderNo, String from, String to, String event, String operator) {
    if (!isAllowed(from, to)) throw BizException.of(409, "非法状态流转");
    if (orderMapper.casStatus(orderNo, from, to) == 0) return false;
    logMapper.insert(new OrderStatusLog(orderNo, from, to, event, operator, now));
    return true;
}
```

### 8.4 逻辑解析
状态变更**只有一个入口**：所有流转（支付回调 PAY_NOTIFY / 用户取消 USER_CANCEL / 超时关单 TIMEOUT_REAPER / 库存不足 STOCK_INSUFFICIENT）都走 `transit()`——合法性集中校验、CAS 防并发跳变、流水表只增不改可审计。与简历能源项目的策略模式是同一思想的不同形态：**把变化的维度收敛到独立单元，新增场景只加不改**。

### 8.5 面试话术
> "能源项目我用策略模式解耦 18 类状态更新；商城项目里同样的问题我用状态机解：所有订单流转收敛到唯一入口，转移表集中校验，CAS 执行，每笔流转写审计流水——排查问题时 `order_status_log` 一查到底。两个项目让我对『开闭原则怎么落地』有两套实证。"

---

## 九、Docker / Git / Maven（工程化）

### 9.1 简历原文
> "熟悉 Linux、Jenkins、Git、SVN、Maven 等常用工具"

### 9.2 项目哪里用
- **Docker Compose**：6 容器（Nacos/Redis/ES/RocketMQ-broker/namesrv/Seata）一键编排，restart:always
- **Git**：10+ 里程碑提交全在 GitHub（github.com/alexbibihere/mall-project）
- **Maven 多模块**：8 模块聚合（common/user/product/cart/order/seckill/search/gateway）
- **质量保障**：16 步冒烟脚本 + 压测脚本 + 仿真数据脚本（`scripts/`）

### 9.3 面试话术
> "工程化我用 Docker Compose 把中间件全部容器化编排，Maven 管理多模块依赖，每个里程碑都有 16 步冒烟回归—— Seata 换本地消息表、分库分表上线，都是冒烟脚本先证明『没破坏任何现有链路』再合入的。"

---

## 十、反哺简历：商城项目可新增的简历亮点（建议直接抄）

简历目前**没有分库分表**——这是大厂 JD 高频词，商城项目正好补上：

1. **基于 ShardingSphere 实现订单分库分表（2 库×2 表），采用基因法将 user_id 基因嵌入订单号，解决 buyer/订单号双维度跨片查询；存量 2090 行平滑迁移，新单分片路由验证 100% 正确**
2. **下单链路从 Seata AT 重构为本地消息表+MQ 最终一致（压测 AT 吞吐下降 86.6% 为决策依据），任务表 CAS 幂等 + 补偿扫描 + 对账中心三层兜底，消息零丢失**
3. **Redis Lua 原子扣减 + 库存分桶抗热点，压测 89 TPS 零超卖；多级缓存热读 3036 QPS**
4. **Elasticsearch+IK 商品搜索：MQ 事务后同步 + 全量 reindex 对账，DB/ES 最终一致**

---

## 附：技术 → 代码文件速查表

| 技术 | 文件 |
|---|---|
| Lua 扣减 | mall-seckill-service/src/main/resources/lua/seckill_deduct.lua |
| 分桶服务 | mall-seckill-service/.../service/SeckillStockService.java |
| 本地消息表下单 | mall-order-service/.../service/OrderService.java |
| CAS 消费 | mall-order-service/.../consumer/StockDeductTaskConsumer.java |
| 状态机 | mall-order-service/.../service/OrderStateMachine.java |
| 分库分表 | mall-order-service/.../config/ShardingSphereConfig.java |
| 基因法 | mall-common/.../SnowflakeIdGenerator.java |
| 限流 | mall-common/.../sentinel/ParamFlowRuleManager.java |
| 幂等 | mall-common/.../aspect/IdempotentAspect.java |
| 超时关单 | mall-order-service/.../service/OrderTimeoutReaper.java |
| 对账 | mall-order-service/.../reconcile/OrderReconcileTask.java、mall-seckill-service/.../reconcile/StockReconcileTask.java |
| ES | mall-search-service/.../service/ProductSearchService.java |
| 网关 | mall-gateway/.../filter/AuthGlobalFilter.java、CorsConfig.java |
| MQ 同步 | mall-product-service/.../mq/ProductEventPublisher.java |
