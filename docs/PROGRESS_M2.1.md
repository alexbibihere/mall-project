# M2.2 进行中 · 任务进度看板（承接 M2.1 已验收）

> 最后更新：2026-09-20 13:10 ｜ 本文件是跨会话进度锚点，开工前先读我
> 📋 未完成任务清单：[PENDING_TASKS.md](PENDING_TASKS.md)（P0 收尾 / P1 M2.3 / P2 M2.4 / P3 加分项）

## 当前状态：M2 全里程碑收官（M2.1~M2.4 ✅），冒烟 16/16，压测报告 docs/BENCH_M2_REPORT.md；下一步 M3

## ✅ M2.2 本轮完成（2026-09-20）

### 1. Seata AT 试点（下单跨服务扣库存）✅
- Seata Server 2.0.0：Docker 容器 `mall-seata`（镜像 `docker.m.daocloud.io/seataio/seata-server:2.0.0`，:8091，standalone file 模式）
- `OrderService.create`：删除 M2.1 手写补偿循环 → `@GlobalTransactional(name="mall-order-create", rollbackFor=Exception.class, timeoutMills=60000)`
- 分支方 product：`SeataConfig`（HandlerInterceptor 从 `TX_XID` 头绑定 `RootContext` + afterCompletion unbind）；`InternalStockController.deduct` 的扣减 SQL 自动注册 AT 分支写 undo_log
- XID 传播：order 侧 `SeataFeignConfig`（Feign RequestInterceptor 发 `TX_XID` 头）——不依赖 seata-http 模块
- 依赖：`io.seata:seata-spring-boot-starter:2.0.0`（**SCA 的 spring-cloud-starter-alibaba-seata 没有 2.0.0 版本**，勿用）
- `sql/undo_log.sql` 已在 mall 库执行；种子新增低库存 SKU id=4（stock=1）专供回滚冒烟
- 冒烟第 13/14 步：同 SKU 两件下单（第 1 件成功→第 2 件失败）→ 全局回滚 → 库存回补一致 stock=1 ✓
- 日志实证：`branch register success, lockKeys:product:4` → `undo_log deleted with GlobalFinished` → `PhaseTwo_Rollbacked`

### 2. Sentinel 参数级流控（替换自研 RateLimitAspect）✅
- 新增 `mall-common/sentinel/ParamFlowRuleManager`：@RateLimit 注解接口不变（limit/windowSeconds），底层改走 `sentinel_param_flow.lua`（Redis ZSET 滑窗，对齐 ParamFlowRule「参数维度」语义：同一用户每秒 N 次）
- 原 `RateLimitAspect` 已不再被引用（保留文件待后续删除）；唯一使用点 seckill `POST /api/seckill/orders`（5次/秒/用户）已自动切换

### 3. OpenFeign 拆链完善 ✅（最小闭环）
- XID 随 Feign 传播（见上）；/internal/** 鉴权现状：网络隔离（网关不路由）+ AuthInterceptor 放行清单，正式内部鉴权（internal-token/mTLS）留 M2.3+

## 🔧 本次踩坑（重要！）

| 坑 | 解法 |
|---|---|
| `spring-cloud-starter-alibaba-seata:2.0.0` 不存在 | 直接用 `io.seata:seata-spring-boot-starter:2.0.0`，XID 传播自己写（Feign 拦截器 + HandlerInterceptor） |
| Seata 2.0 把业务异常包成 `RuntimeException("try to proceed invocation error")` → 500 | GlobalExceptionHandler 加 RuntimeException 处理器，沿 cause 链解包出 BizException 返回真实码（如 409） |
| GlobalTransactional 失效排查 | 必须确认日志里有 `Branch register success, lockKeys:...`（分支注册成功才算接上） |
| hermes patch 模糊匹配**静默失败**（锚点不存在时） | 每次 patch 后必须读回验证，或用 diff 输出确认 |
| 冒烟第 11 步撞服务重启竞态 | order 重启后 RocketMQ 消费者订阅未就绪，秒杀单落库慢 → 轮询容忍「订单不存在」继续等 |
| 华为云/阿里云都下不到 seata-server 完整发行包 | 走 DaoCloud 拉 `seataio/seata-server` 官方镜像 |
| git-bash 里 mvn 必须用 `mvn.cmd`（sh 版必炸 classworlds） | JAVA_HOME 需覆盖 jdk-21；重打包前先停占用 jar 的服务 |

## 📌 当前进程布局（6 服务 + seata，均在跑）

| 服务 | session_id | 端口 |
|---|---|---|
| user | proc_d0bbb81ea4ee | 8101 |
| product | proc_75368a527c8a | 8102 |
| cart | proc_202d5da1af00 | 8103 |
| order | proc_be33c6b1ecb7 | 8104 |
| seckill | proc_bbc3119b6a03 | 8105 |
| gateway | proc_19c23097f5df | 9000 |
| seata-server | docker 容器 mall-seata | 8091 |

## 🔜 M2.2 收尾清单（下会话从这里接）

1. ✅ git 提交 M2.2 已完成（f692a95，2026-09-20）
2. （可选）删除 `RateLimitAspect.java` 死代码 + `LegacyAuthInterceptor.java` 确认无用后清理
3. （可选）压测对比：同步链路接入 Seata 后的 QPS/P99 vs M1 基线（QPS 167.6 / P99 523ms），预期有损耗，写入 M2.4 报告素材
4. M2.3：ES 8 商品搜索全链路（IK 分词/MQ 同步/检索聚合建议）

## 📜 M2.1 验收存档（2026-09-20，git 5795e21）

- 冒烟 12 步经网关 9000 全绿；6/6 服务注册 Nacos；seckill 修复记录（@EnableFeignClients + loadbalancer 依赖 + lua 归位）见 git history
