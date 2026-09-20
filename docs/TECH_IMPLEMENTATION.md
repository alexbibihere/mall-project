# 技术实现文档 —— 每项技术·设计思路·核心代码

> 配套：[TECH_INTERVIEW_QA.md](TECH_INTERVIEW_QA.md)（问答版）/ [INTERVIEW_GUIDE.md](INTERVIEW_GUIDE.md)（叙事版）
> 本文特点：**每个技术点都附带项目中的真实核心代码**，可直接对照讲实现。

---

## 1. Redis Lua 原子扣减 + 库存分桶（防超卖核心）

**解决什么**：秒杀场景「查库存→判限购→扣减→记录用户」多步操作，若分开执行必然超卖/超卖+超购。

**实现**：整个判定链路放进一个 Lua 脚本，Redis 单线程保证原子性；库存拆 N 桶，请求按 userId 哈希路由起始桶，售罄自动顺延下一桶。

```lua
-- mall-seckill-service/src/main/resources/lua/seckill_deduct.lua
-- KEYS[1..N]  : 库存分桶 key（stock:seckill:{pid}:b0 ~ b{N-1}）
-- KEYS[N+1]   : 已购用户集合（stock:seckill:{pid}:users）
-- ARGV: [1]qty [2]userId [3]路由哈希
local qty = tonumber(ARGV[1])
local userId = ARGV[2]
local routeHash = tonumber(ARGV[3])
local userSet = KEYS[#KEYS]
local n = #KEYS - 1

if redis.call('SISMEMBER', userSet, userId) == 1 then
  return 'DUP'                                  -- 一人一单：重复直接拒
end

local start = routeHash % n                     -- 哈希路由起始桶，防集中热
for i = 0, n - 1 do
  local idx = (start + i) % n
  local remain = tonumber(redis.call('GET', KEYS[idx + 1]) or '0')
  if remain >= qty then
    redis.call('DECRBY', KEYS[idx + 1], qty)
    redis.call('SADD', userSet, userId)
    return 'OK|' .. idx .. '|' .. (remain - qty)
  end
end
return 'SOLDOUT'                                -- 全桶遍历仍不足才判售罄
```

**要点**：① 单线程原子消除竞态 ② 分桶消除单 key 热 ③ 售罄顺延 ④ 返回桶序号供回补定位。**验证**：压测受理=Redis消耗=DB消耗，零超卖。

---

## 2. 本地消息表 + MQ 最终一致（替代 Seata AT）

**解决什么**：跨服务扣库存的分布式一致性。Seata AT 实测使下单吞吐 **-86.6%**（167.6→22.5 QPS），弃用。

**核心思想**：把"一致性成本"从调用链路挪到对账兜底——本地事务保证「订单成功 ⇒ 扣减任务必达」，之后异步执行。

### 2.1 生产端：订单与任务同事务 + afterCommit 发 MQ

```java
// mall-order-service/.../service/OrderService.java（节选）
@Transactional
public Order create(Long userId, Long addressId, List<OrderItemInput> items) {
    // 1~3. Feign 校验：地址快照 / 商品快照锁价 / 乐观预检(soft check)
    // 4. 订单落库——基因法订单号：末位=user_id%10（分片路由用）
    order.setOrderNo(idGen.nextOrderNo(userId));
    orderMapper.insert(order);
    orderItems.forEach(orderItemMapper::insert);

    // 5. 本地消息表：与订单同事务（订单成功 => 扣减任务必达）
    for (OrderItem oi : orderItems) {
        OrderStockTask t = new OrderStockTask();
        t.setOrderNo(order.getOrderNo());
        t.setProductId(oi.getProductId());
        t.setQuantity(oi.getQuantity());
        t.setStatus(OrderStockTask.ST_INIT);
        stockTaskMapper.insert(t);
    }

    // 6. 事务提交后发 MQ；发送失败不回滚订单——补偿任务会重扫 INIT 重发
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            for (OrderStockTask t : sentTasks) {
                try {
                    rocketMQTemplate.syncSend(MqTopics.ORDER_STOCK_DEDUCT_TOPIC,
                            MessageBuilder.withPayload(t.getId()).build());
                } catch (Exception e) {
                    log.warn("mq send failed, taskId={} (补偿任务会重发)", t.getId());
                }
            }
        }
    });
    return order;
}
```

### 2.2 消费端：CAS 抢任务 = 天然幂等 + 库存不足关单

```java
// mall-order-service/.../consumer/StockDeductTaskConsumer.java（节选）
@Override
public void onMessage(Long taskId) {
    OrderStockTask task = taskMapper.selectById(taskId);

    // 幂等核心：CAS 抢任务（INIT/FAILED -> PROCESSING），抢不到=已被处理
    if (taskMapper.casGrab(taskId) == 0) return;

    // 真扣减（product 乐观锁二次兜底）
    Result<Boolean> r = productInternalClient.deduct(task.getProductId(), task.getQuantity());
    if (r.getCode() == 0 && Boolean.TRUE.equals(r.getData())) {
        markDone(taskId);                         // 置 DONE
        return;
    }

    // 库存不足：等兄弟任务终态（防竞态）→ 只回补已 DONE 行（防超补）→ 状态机关单
    waitSiblingsTerminal(task);
    restoreDoneSiblings(task);
    stateMachine.transit(task.getOrderNo(), Order.ST_UNPAID,
            Order.ST_CANCELLED, "STOCK_INSUFFICIENT", "system");
    fail(task, "INSUFFICIENT");
}
```

```java
// CAS 抢任务的 SQL——同一消息重投/并发消费只有一方成功
@Update("UPDATE order_stock_task SET status='PROCESSING', retries=retries+1 " +
        "WHERE id=#{id} AND status IN ('INIT','FAILED')")
int casGrab(@Param("id") Long id);
```

**消息不丢三保险**：① 任务表先于发送持久化 ② `StockTaskCompensator` 每 10s 重扫 INIT/PROCESSING 滞留重发 ③ 对账任务 5 分钟终局校验。

---

## 3. 订单状态机 + 流水审计

**解决什么**：状态流转散落各处、非法流转不可控、变更无审计。

```java
// mall-order-service/.../service/OrderStateMachine.java
/** 合法性：UNPAID→PAID/CANCELLED；PAID→CANCELLED(退款预留)；CANCELLED 终态 */
public static boolean isAllowed(String from, String to) {
    if (Order.ST_UNPAID.equals(from))
        return Order.ST_PAID.equals(to) || Order.ST_CANCELLED.equals(to);
    if (Order.ST_PAID.equals(from))
        return Order.ST_CANCELLED.equals(to);
    return false;
}

/** CAS 流转 + 流水记录（同一事务） */
public boolean transit(String orderNo, String from, String to, String event, String operator) {
    if (!isAllowed(from, to))
        throw BizException.of(409, "非法状态流转: " + from + " -> " + to);
    if (orderMapper.casStatus(orderNo, from, to) == 0)
        return false;                              // 并发下他人已流转
    OrderStatusLog line = new OrderStatusLog();    // 只增不改的审计流水
    line.setOrderNo(orderNo); line.setFromStatus(from); line.setToStatus(to);
    line.setEvent(event); line.setOperator(operator);
    logMapper.insert(line);
    return true;
}
```

```java
// OrderMapper —— 状态机 CAS 原语：并发支付/取消只有一方生效，天然幂等
@Update("UPDATE orders SET status=#{to} WHERE order_no=#{orderNo} AND status=#{from}")
int casStatus(String orderNo, String from, String to);
```

三个流转点统一接入：`USER_CANCEL`（用户取消）、`PAY_NOTIFY`（支付回调）、`TIMEOUT_REAPER`（超时关单）、`STOCK_INSUFFICIENT`（库存不足关单）。

---

## 4. 支付回调三重防资损

```java
// mall-order-service/.../service/PaymentService.java
@Transactional
public Map<String, Object> mockNotify(Long paymentId) {
    // ① 支付单 CAS 幂等：UNPAID->PAID 抢不到 = 重复回调，幂等返回
    int affected = paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
            .eq(Payment::getId, paymentId)
            .eq(Payment::getStatus, Payment.ST_UNPAID)
            .set(Payment::getStatus, Payment.ST_PAID));
    if (affected == 0) return Map.of("duplicate", true);

    // ② 金额校验：回调金额 ≠ 应付金额即异常，绝不放行
    if (order.getPayAmount().compareTo(p.getAmount()) != 0)
        throw BizException.of(500, "回调金额异常");

    // ③ 订单状态机二次 CAS：与取消/关单并发只有一方胜出
    boolean transit = stateMachine.transit(order.getOrderNo(),
            Order.ST_UNPAID, Order.ST_PAID, "PAY_NOTIFY", "system");
    if (!transit)
        throw BizException.of(409, "订单已取消，支付转退款处理");
    return Map.of("duplicate", false, "orderNo", order.getOrderNo());
}
```

---

## 5. 分库分表 + 基因法（ShardingSphere 5.5.2 编程式配置）

**解决什么**：单表容量天花板；buyer/订单号双维度查询免跨片。

**基因法**：订单号末位嵌入 `user_id % 10` 基因 → 按 user_id 分片与按订单号基因分片**恒等同片**。

```java
// mall-common/.../SnowflakeIdGenerator.java —— 基因注入
public String nextOrderNo(long userId) {
    long gene = Math.abs(userId % 10);
    return "SO" + nextId() + gene;   // 订单号末位 = user_id % 10
}
```

```java
// mall-order-service/.../config/ShardingSphereConfig.java（节选，编程式绕开 snakeyaml 冲突）
ShardingTableRuleConfiguration orders =
        new ShardingTableRuleConfiguration("orders", "ds${0..1}.orders_${0..1}");
orders.setDatabaseShardingStrategy(
        new StandardShardingStrategyConfiguration("user_id", "db-mod"));   // user_id%2 选库
orders.setTableShardingStrategy(
        new StandardShardingStrategyConfiguration("user_id", "tbl-mod-orders")); // %2 选表
orders.setKeyGenerateStrategy(new KeyGenerateStrategyConfiguration("id", "snowflake"));

// order_status_log 按订单号基因路由（末位%2 与 user_id%2 恒等 → 天然同片）
ShardingTableRuleConfiguration logs = new ShardingTableRuleConfiguration(
        "order_status_log", "ds${0..1}.order_status_log_${0..1}");
logs.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("order_no", "gene-db"));
logs.setTableShardingStrategy(new StandardShardingStrategyConfiguration("order_no", "gene-tbl"));

// 行表达式算法
rule.getShardingAlgorithms().put("db-mod",  inlineAlg("ds${user_id % 2}"));
rule.getShardingAlgorithms().put("gene-db", inlineAlg("ds${new Integer(order_no.substring(order_no.length()-1)) % 2}"));

// 5.5.x 严格校验：未分片表必须显式注册（否则 TableNotFoundException）
SingleRuleConfiguration single = new SingleRuleConfiguration(
        List.of("ds0.payment", "ds0.order_stock_task", ...), "ds0");
return ShardingSphereDataSourceFactory.createDataSource(dsMap, List.of(rule, single), props);
```

**踩坑实录**（面试加分）：5.4→5.5 坐标改名 `shardingsphere-jdbc-core`→`shardingsphere-jdbc`；YAML 引擎 snakeyaml 1.x 与 Boot3 2.x 冲突→改编程式；5.5 单表严格校验；缺失 reactor-core 传递依赖。

---

## 6. 限流：Redis+Lua 滑动窗口（Sentinel ParamFlow 语义）

**接口不变、底层替换**——`@RateLimit(limit=5, windowSeconds=1)` 注解挂在秒杀接口，切面拦截：

```java
// mall-common/.../sentinel/ParamFlowRuleManager.java（节选）
@Before("@annotation(rateLimit)")
public void checkParamFlow(JoinPoint jp, RateLimit rateLimit) {
    Long userId = UserContext.get();                       // 参数维度：按用户
    String key = "flow:" + resource + "__" + userId;
    Long allowed = redis.execute(PARAM_FLOW, List.of(key),
            String.valueOf(now), String.valueOf(rateLimit.windowSeconds()),
            String.valueOf(rateLimit.limit()), member);
    if (allowed == null || allowed == 0L)
        throw new BizException(429, "请求过于频繁，请稍后再试");
}
```

```lua
-- mall-common/src/main/resources/sentinel_param_flow.lua —— ZSET 滑动窗口
redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)  -- 清窗外的
local cnt = redis.call('ZCARD', key)
if cnt >= limit then return 0 end
redis.call('ZADD', key, now, member)                          -- 记本次
redis.call('PEXPIRE', key, window * 1000 + 1000)
return 1
```

**实测**：200 请求压测拦截 120（限购）+30（限流），防护链全部生效。

---

## 7. 幂等体系（三种场景三种实现）

| 场景 | 实现 | 代码位置 |
|---|---|---|
| 秒杀防重放 | 幂等令牌（一次性 setnx）`@Idempotent` | SeckillController + IdempotentAspect |
| 一人一单 | Lua 内 SISMEMBER 原子判定 | seckill_deduct.lua |
| 支付重复回调 | 支付单 CAS | PaymentService.mockNotify |
| 扣减任务重投 | 任务表 CAS 抢占 | StockDeductTaskConsumer |

```java
// 幂等令牌使用：先取 token，下单携带，消费即失效（409 防重放）
@PostMapping("/execute")
public Result<...> execute(@RequestHeader("X-Idempotent-Token") String token, ...) {
    stockService.executeWithToken(productId, userId, token, quantity);
}
```

---

## 8. Elasticsearch 搜索（IK 分词 + MQ 同步 + 聚合）

**索引 mapping**（构建期校验分词器，写入细粒度/检索粗粒度）：

```java
// mall-search-service/.../ProductSearchService.java
private static final String INDEX_MAPPING = """
  "analysis": { "analyzer": {
      "ik_max_analyzer": { "type": "custom", "tokenizer": "ik_max_word" },
      "ik_smart_analyzer": { "type": "custom", "tokenizer": "ik_smart" } } },
  "properties": {
    "name":  { "type": "text", "analyzer": "ik_max_analyzer",
               "search_analyzer": "ik_smart_analyzer",
               "fields": { "kw": { "type": "keyword" } } },
    "price": { "type": "scaled_float", "scaling_factor": 100 } }
""";
```

**检索**（multiMatch 权重 + filter 不算分 + facets 聚合一次请求）：

```java
bf.must(m -> m.multiMatch(mm -> mm.query(q)
        .fields(List.of("name^3", "brand^2", "category"))));   // name 权重最高
bf.filter(f -> f.term(t -> t.field("category.kw").value(category)));  // filter 不算分可缓存
s.aggregations("categories", a -> a.terms(t -> t.field("category.kw").size(20)));
```

**同步链路**：product 库存扣减/回补 → `afterCommit` 发 `PRODUCT_CHANGED_TOPIC` → search 消费 upsert（日志实证 `es upserted`）；启动全量 reindex + `/internal/search/reindex` 对账兜底。

---

## 9. 分布式 ID：雪花 + 基因法

```java
// mall-common/.../SnowflakeIdGenerator.java
public synchronized long nextId() {
    long now = System.currentTimeMillis();
    if (now < lastTimestamp) now = lastTimestamp;      // 时钟回拨：小幅等待追平
    if (now == lastTimestamp) {
        sequence = (sequence + 1) & MAX_SEQUENCE;
        if (sequence == 0)
            while ((now = System.currentTimeMillis()) <= lastTimestamp) {} // 自旋
    } else sequence = 0L;
    lastTimestamp = now;
    return ((now - EPOCH) << TIMESTAMP_SHIFT) | (machineId << MACHINE_SHIFT) | sequence;
}
```

结构：1bit 符号 + 41bit 时间戳 + 10bit 机器 + 12bit 序列。时钟回拨小幅等待、序列溢出自旋下一毫秒。

---

## 10. 对账中心（最终一致的"最终"由它兜底）

```java
// mall-order-service/.../reconcile/OrderReconcileTask.java（每 5 分钟）
for (Order order : recent) {
    for (OrderStockTask t : tasks) {
        switch (order.getStatus()) {
            case ST_CANCELLED -> { /* ① 关单任务滞留 → 强制 FAILED 终态 */ }
            case ST_UNPAID    -> { /* ② INIT 滞留 → 重发 MQ（补偿之外第二道） */ }
            case ST_PAID      -> { /* ③ 非 DONE → log.error 资损告警，人工介入 */ }
        }
    }
}
```

```java
// mall-seckill-service/.../reconcile/StockReconcileTask.java（每 5 分钟）
// 活动口径：本活动消耗 = 活动库存 - 分桶余量；DB 消耗 = 预热时快照 - 当前 DB
long consumedRedis = baseline.activityStock() - redisRemain;
long consumedDb    = baseline.dbStockAtWarmup() - dbStock;
long drift = consumedRedis - consumedDb;
// 容差带 [0,200] = MQ 在途；超带告警。实测 drift=0
```

---

## 11. 补偿与关单（自愈能力）

```java
// 补偿：每 10s 重扫滞留任务重发（消息不丢的最后一道）
@Scheduled(fixedDelay = 10_000)
public void resend() {
    List<OrderStockTask> stuck = taskMapper.selectList(new LambdaQueryWrapper<OrderStockTask>()
            .in(OrderStockTask::getStatus, ST_INIT, ST_PROCESSING)
            .lt(OrderStockTask::getUpdatedAt, LocalDateTime.now().minusSeconds(30))
            .last("LIMIT 100"));
    stuck.forEach(t -> rocketMQTemplate.syncSend(TOPIC, MessageBuilder.withPayload(t.getId()).build()));
}

// 超时关单：Redisson 看门狗锁防多实例重复关（自动续期，替代手写 setnx+TTL）
RLock lock = redisson.getLock("lock:order:close:" + order.getOrderNo());
if (lock.tryLock(0, TimeUnit.SECONDS)) {          // 不等待：抢不到=有人在处理
    try { orderService.closeIfTimeout(order.getOrderNo()); }
    finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
}
```

---

## 12. 多级缓存读链路（热读 3036 QPS / P99 47.6ms 的来源）

```
请求 → Caffeine 本地缓存（热点 95%+ 命中，进程内纳秒级）
     → Redis（分布式缓存，毫秒级，分桶 key）
     → MySQL（回填 + 空值缓存 60s 防穿透）
```

- **虚拟线程**（product 开启）：I/O 密集读链路挂起不占平台线程
- **一致性**：先更 DB 再删缓存；商品变更走 MQ 事件失效
- 空值防穿透、TTL 抖动防雪崩、本地兜底防击穿

---

## 附：技术→代码文件速查

| 技术 | 文件 |
|---|---|
| Lua 分桶扣减 | `mall-seckill-service/src/main/resources/lua/seckill_deduct.lua` |
| 限流 Lua | `mall-common/src/main/resources/sentinel_param_flow.lua` |
| 本地消息表下单 | `mall-order-service/.../service/OrderService.java` |
| CAS 幂等消费 | `mall-order-service/.../consumer/StockDeductTaskConsumer.java` |
| 状态机 | `mall-order-service/.../service/OrderStateMachine.java` |
| 支付三重防资损 | `mall-order-service/.../service/PaymentService.java` |
| 分库分表 | `mall-order-service/.../config/ShardingSphereConfig.java` |
| 雪花+基因法 | `mall-common/.../SnowflakeIdGenerator.java` |
| 参数级限流 | `mall-common/.../sentinel/ParamFlowRuleManager.java` |
| 订单对账 | `mall-order-service/.../reconcile/OrderReconcileTask.java` |
| 库存对账 | `mall-seckill-service/.../reconcile/StockReconcileTask.java` |
| 超时关单 | `mall-order-service/.../service/OrderTimeoutReaper.java` |
| ES 检索同步 | `mall-search-service/.../service/ProductSearchService.java` |
