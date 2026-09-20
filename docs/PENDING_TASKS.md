# 未完成任务进度列表（PENDING TASKS）

> 创建：2026-09-20 ｜ **更新：P0 收尾 ✅ + M2.3 ES 搜索 ✅（冒烟 16/16）** ｜ 配套看板：[PROGRESS_M2.1.md](PROGRESS_M2.1.md)
> 里程碑进度：M1 ✅ → M1.5 ✅ → M2.1~M2.3 ✅ (5795e21/f692a95/99ded9d/141e77f) → **M2.4 ✅ 报告落盘** → M3 ⬜

---

## P0 · M2.2 收尾 ✅ 全部完成（2026-09-20，git 见 log）

### ✅ 1. 删除死代码 `RateLimitAspect.java`
- 现状：M2.2 已被 `ParamFlowRuleManager` 取代，grep 全仓 **0 引用**（2026-09-20 已核实）
- 位置：`mall-common/src/main/java/com/mall/common/aspect/RateLimitAspect.java`
- 动作：直接删除；`@RateLimit` / `@Idempotent` 注解保留（还在用）
- 验收：`mvn.cmd -q package -DskipTests` 全绿 + 冒烟第 9/10 步（限流/幂等）不回归

### ✅ 2. 清理 `LegacyAuthInterceptor.java`
- 现状：grep 全仓 **0 引用**，疑似 M2.1 迁移时的旧版备份（2026-09-20 已核实）
- 位置：`mall-common/src/main/java/com/mall/common/LegacyAuthInterceptor.java`
- 动作：人工确认后删除；顺带检查 `mall-common` 里 `com.mall.cart.view.CartItemView` 放 common 是否合理（cart 专属 DTO 下沉 common 的理由，面试会被问）

### ✅ 3. seata 纳入 docker-compose
- 现状：seata-server 是 `docker run` 手工起的裸容器（无 `--restart` 策略，机器重启不自启）；`deploy/docker-compose.yml` 里只有 redis/namesrv/broker/nacos
- 动作：compose 增加 `seata-server` 服务（镜像 `docker.m.daocloud.io/seataio/seata-server:2.0.0`），顺手补 `- 7091:7091`（控制台 UI，当前宿主机访问不了）
- 验收：`docker compose down && docker compose up -d` 一条命令拉起全套基础设施

### ✅ 4. Seata AT 性能损耗实测（2026-09-20，threads=50 total=300）

**结果：QPS 22.5（M1 单体 167.6，-86.6%）｜ P99 4286.7ms（M1 523ms）｜ 300/300 成功**

损耗归因（order 日志实证）：每笔订单 AT 全链 = 分支注册×4 + undo_log 前后镜像×4 + TC 往返×3（begin/register/commit），日志 5+ 条/单；叠加 Feign 串行 4 跳。秒杀链路（Redis 预扣 + MQ 异步）不受 AT 影响。优化方向：TCC / 消息最终一致——M2.4 报告核心素材已就位。

---

## P1 · M2.3：ES 8 商品搜索全链路 ✅ 完成（2026-09-20，冒烟 16/16 全绿）

| # | 任务 | 结果 |
|---|---|---|
| 1 | ES 8.11.4 容器 + IK 分词 | ✅ compose 托管 mall-es（DaoCloud 镜像，512m 堆，数据卷持久化）；IK 走 infinilabs 官方源（阿里云 maven 已 404）；`_analyze` 验证：无线蓝牙耳机→[无线,蓝牙,耳机] |
| 2 | 索引模型 mall_products | ✅ name/brand/category=text(ik_max_word索引/ik_smart检索)+keyword子字段；price=scaled_float(100)；stock=integer；mapping 内嵌 ProductSearchService，启动幂等建索引 |
| 3 | 数据同步链路 | ✅ product 扣减/回补 afterCommit 发 PRODUCT_CHANGED_TOPIC → search 消费 upsert（日志实证 es upserted）；启动全量 reindex + /internal/search/reindex 对账接口 |
| 4 | 搜索接口 | ✅ GET /api/search?q&category&brand&minPrice&maxPrice&sort：multiMatch(name^3>brand^2>category)+term filter+range+分页+sort+categories/brands 聚合 |
| 5 | 网关路由 | ✅ /api/search/** 路由+白名单；全局 QPS 流控移 P3 |
| 6 | 提交 | ✅ 冒烟 16/16（新增 [15] 搜索+聚合命中 facets=[('家居',1)]、[16] MQ 增量同步 ES 库存一致） |

### M2.3 踩坑（重要）
- **WPS 云服务(wpscloudsvr.exe)抢占 8102/8103** → product 改 8112、cart 改 8113；此软件常驻会随机抢端口，服务「端口幽灵占用」先查它
- ES Java Client 8.11 API：RangeQuery 用 `gte(JsonData)/lte(JsonData)`（无 number()）；terms 聚合 keyword 字段用 `sterms`（bucket.key() 直接是 String，无需 stringValue）
- python http.client 请求行不支持非 ASCII → 中文查询参数必须 urllib.parse.quote
- 模块单独 package 需先 install parent(-N)+common，否则远程仓库缓存污染报 not found

---

## P2 · M2.4：微服务化压测对比报告 ✅ 完成（2026-09-20，docs/BENCH_M2_REPORT.md）

- ✅ 秒杀受理压测：34.1 TPS（50 用户抢 200 库存），**零超卖**（受理=Redis消耗=DB消耗=50），限流拒 167 + 限购拒 283
- ✅ 缓存热读：QPS 3036.8 / P99 47.6ms / 2500 请求 0 失败
- ✅ 报告落盘 docs/BENCH_M2_REPORT.md：-86.6% 归因排序（AT > Feign 串行 > 网关）+ 优化路线（去AT改事务消息→Feign并行→静态化→分库分表）
- M2 里程碑至此全部收官（M2.1~M2.4）✅

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
| 同步下单 QPS | 167.6 | **22.5**（含 Seata AT，P0-4 实测） |
| 同步下单 P99 | 523ms | 4286.7ms |
| 秒杀受理 TPS | — | 34.1（受理口径，零超卖 ✓） |
| 缓存热读 QPS | 5224~6504 | 3036.8（P99 47.6ms） |
| 超卖 | 0 | 0（冒烟持续验证） |

> 环境/进程布局/构建铁律见 PROGRESS_M2.1.md「环境坑」表。
