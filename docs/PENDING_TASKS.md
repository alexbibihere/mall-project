# 未完成任务进度列表（PENDING TASKS）

> 创建：2026-09-20 ｜ 配套看板：[PROGRESS_M2.1.md](PROGRESS_M2.1.md)
> 里程碑进度：M1 ✅ → M1.5 ✅ → M2.1 ✅ (5795e21) → **M2.2 ✅ (f692a95)** → M2.3 ⬜ → M2.4 ⬜

---

## P0 · M2.2 收尾（下次会话第一步，预计 30 分钟）

### ⬜ 1. 删除死代码 `RateLimitAspect.java`
- 现状：M2.2 已被 `ParamFlowRuleManager` 取代，grep 全仓 **0 引用**（2026-09-20 已核实）
- 位置：`mall-common/src/main/java/com/mall/common/aspect/RateLimitAspect.java`
- 动作：直接删除；`@RateLimit` / `@Idempotent` 注解保留（还在用）
- 验收：`mvn.cmd -q package -DskipTests` 全绿 + 冒烟第 9/10 步（限流/幂等）不回归

### ⬜ 2. 清理 `LegacyAuthInterceptor.java`
- 现状：grep 全仓 **0 引用**，疑似 M2.1 迁移时的旧版备份（2026-09-20 已核实）
- 位置：`mall-common/src/main/java/com/mall/common/LegacyAuthInterceptor.java`
- 动作：人工确认后删除；顺带检查 `mall-common` 里 `com.mall.cart.view.CartItemView` 放 common 是否合理（cart 专属 DTO 下沉 common 的理由，面试会被问）

### ⬜ 3. seata 纳入 docker-compose
- 现状：seata-server 是 `docker run` 手工起的裸容器（无 `--restart` 策略，机器重启不自启）；`deploy/docker-compose.yml` 里只有 redis/namesrv/broker/nacos
- 动作：compose 增加 `seata-server` 服务（镜像 `docker.m.daocloud.io/seataio/seata-server:2.0.0`），顺手补 `- 7091:7091`（控制台 UI，当前宿主机访问不了）
- 验收：`docker compose down && docker compose up -d` 一条命令拉起全套基础设施

### ⬜ 4. Seata 接入后的性能损耗速测（喂给 M2.4）
- 现状：同步下单链路已挂 `@GlobalTransactional`，AT 模式有 undo_log 写入 + 二阶段通信开销，损耗未知
- 动作：`python scripts/mall_bench.py bench --total 500` 跑一轮，与 M1 基线（同步 QPS 167.6 / P99 523ms）对比，数字记入本文档 P2 素材区

---

## P1 · M2.3：ES 8 商品搜索全链路 ⬜（下一个里程碑）

| # | 任务 | 要点 | 验收 |
|---|---|---|---|
| 1 | ES 8 容器 + IK 分词 | DaoCloud 镜像源（已验证可用），`ES_JAVA_OPTS` 限内存；IK 插件离线装入 | 容器 healthy，`_analyze` 中文分词正确 |
| 2 | product 索引模型 | name/brand/category 分词策略 + price/stock 数值字段；mapping 落 `deploy/es/` | 索引创建脚本可重复执行 |
| 3 | 数据同步链路 | 商品变更（增/改/扣减）→ MQ 事件 → search 同步写 ES（复用 rocketmq，新 topic `product-changed`）；对账兜底全量重建接口 `/internal/search/reindex` | 增量延迟 < 1s；对账脚本差集=0 |
| 4 | 搜索接口 | `GET /api/search?q=&category=&minPrice=&maxPrice=&sort=`：分词检索 + 聚合（分类/品牌 facet）+ 搜索建议（completion/pinyin 可选） | 新增冒烟步（搜「马克杯」命中 id=1） |
| 5 | 网关路由 + 限流 | 路由 `/api/search/**`；搜索接口加全局 QPS 流控（区别于用户级 ParamFlow） | 压测通过且触发限流符合预期 |
| 6 | 提交 + 看板 | 冒烟 ≥15 步全绿 | git tag M2.3 |

---

## P2 · M2.4：微服务化压测对比报告 ⬜

- ⬜ 基线复测：M1 单体数据已存档（同步 QPS 167.6 / P99 523ms；秒杀零超卖 P99 80ms；缓存热读 QPS 5224~6504）
- ⬜ M2 微服务形态：同步下单（含 Seata 损耗单列）、秒杀受理链路、缓存热读
- ⬜ 报告落 `docs/BENCH_M2_REPORT.md`：架构演进前后对比 + 瓶颈分析（Feign 串行、AT 全局锁、网关转发）+ 优化路线（并行 Feign/缓存/读写分离）

---

## P3 · 面试加分项（按优先级排序，选做）

1. ⬜ Sentinel Dashboard 接入：实时观测 ParamFlow 指标（现在只有日志，无可视化）
2. ⬜ `/internal/**` 内部鉴权：加 internal-token 头校验（当前仅靠网关不路由的网络隔离，纵深防御缺失——面试必问点）
3. ⬜ Nacos 配置中心：六服务 yml 迁 nacos config，验证动态刷新（如 seckill.bucket-count 热更）
4. ⬜ SQL 版本化：引入 Flyway，把 `sql/` 全部脚本纳管（undo_log 目前是手工执行）
5. ⬜ 秒杀对账：分桶余量 vs DB 实扣 的定时对账任务（防 Lua/DB 漂移）

---

## 🧾 基线数据速查（写报告用）

| 指标 | M1 单体 | M2 微服务 |
|---|---|---|
| 同步下单 QPS | 167.6 | ⬜ 待测（P0-4） |
| 同步下单 P99 | 523ms | ⬜ 待测 |
| 秒杀受理 P99 | 80ms | ⬜ 待测 |
| 缓存热读 QPS | 5224~6504 | ⬜ 待测 |
| 超卖 | 0 | 0（冒烟持续验证） |

> 环境/进程布局/构建铁律见 PROGRESS_M2.1.md「环境坑」表。
