# M2.1 微服务化 · 已验收 ✅ · 任务进度看板

> 最后更新：2026-09-20 12:20 ｜ 本文件是跨会话进度锚点，开工前先读我

## 当前状态：M2.1 完成 100%，冒烟 12/12 经网关全绿，已提交 git

## ✅ 验收结论（2026-09-20）

- 6/6 服务注册 Nacos（user/product/cart/order/seckill/gateway），seckill 此前的「待确认」已实锤
- 冒烟 12 步全部经网关 9000 通过：注册登录/地址/商品/加购/下单/支付回调幂等/秒杀预热/受理/防重放/限购/MQ异步落库(UNPAID)/分桶余量+DB同步扣减
- 同步链路与秒杀链路（M1 + M1.5 语义）在微服务形态下等价复现

### M1+M1.5（已验收，git 2854dbe）
- 单体全链路：冒烟 12/12 ✓ / 同步 QPS 167.6 (P99 523ms) / 秒杀零超卖 ✓ (P99 80ms) / 缓存热读 QPS 5224~6504 / 虚拟线程已开启
- 环境：MySQL(Windows 服务) + Redis/RocketMQ/Nacos 2.4.3(Docker Compose)

### M2.1 落地内容
- ✅ 父 pom 聚合工程：mall-parent 2.0.0 + 7 模块（common/gateway/user/product/cart/order/seckill）
- ✅ 48 个源文件 git mv 迁移到各模块（包名未变），资源文件同步归位
- ✅ 全量编译+打包通过；本次会话补齐 seckill 修复后二进制全绿
- ✅ 跨服务改造：
  - JWT 校验上移网关（AuthGlobalFilter 注入 X-User-Id + 白名单 + 剥离伪造头）
  - 服务层 AuthInterceptor 改为校验 X-User-Id 头（WebConfig 全局注册）
  - FeignUserIdInterceptor 透传用户上下文
  - /internal/** 服务间接口：product(扣减/回补/快照) + user(地址快照) + seckill(分桶回补)
  - MQ 常量下沉 com.mall.common.mq（MqTopics/SeckillMessage）
  - 3 个 Feign 客户端（cart→product, order→product/user/seckill）+ seckill→product

## 🔧 本次会话修复（拉起现场时发现并解决）

| 问题 | 修法 |
|---|---|
| seckill 启动失败：ProductClient 无 Bean | SeckillApplication 补 @EnableFeignClients(basePackages="com.mall") |
| No Feign Client for loadBalancing | seckill pom 显式补 openfeign + loadbalancer（此前靠 mall-common 传递，loadbalancer 彻底缺失） |
| lua/seckill_deduct.lua 不在 classpath（500） | 资源在 git mv 时错落到 order 的 lua/lua/ 双层目录 → git mv 归位 seckill resources/lua/ |
| 冒烟第 11 步 500 | 订单查询已归属 mall-order：脚本改打 /api/orders/{orderNo}，状态从 data.order.status 取 |

## 🧰 环境坑（构建/运行必读）

| 坑 | 解法 |
|---|---|
| git-bash 里 mvn 报 ClassNotFoundException: classworlds Launcher | mvn 的 sh 启动脚本在 MSYS 下有 bug，**一律用 `mvn.cmd`** |
| mvn 报「不支持发行版本 21」 | JAVA_HOME 指 jdk-17，命令行覆盖：`JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd ...` |
| repackage 失败：Unable to rename *.jar | 运行中的服务锁着 jar，**先停服务再打包** |
| SC 2023.0.x 拒绝 Boot 3.5.6 | 六服务 yml 加 spring.cloud.compatibility-verifier.enabled=false（勿动版本） |
| yml 批量追加重复键 DuplicateKeyException | 批量改 yml 用读改写，勿盲插 |
| Mapper 找不到 Bean | 6 个 Mapper 接口补 @Mapper |
| mall.jwt.secret 占位符缺失 | product/cart/seckill 三个 yml 补 mall.jwt 配置 |
| bash 会话 & 后台进程随会话死 | 必须用 Hermes terminal(background=true) 逐个拉起服务；服务重启后 Nacos 注册约几秒内恢复 |

## 📌 当前进程布局（6 个 Hermes 后台进程，均在跑）

| 服务 | session_id | 端口 |
|---|---|---|
| user | proc_d0bbb81ea4ee | 8101 |
| product | proc_7a0eb61c22c9 | 8102 |
| cart | proc_202d5da1af00 | 8103 |
| order | proc_2399a68fe854 | 8104 |
| seckill | proc_bbc3119b6a03 | 8105（修复后重建） |
| gateway | proc_19c23097f5df | 9000 |

> 旧僵尸网关 PID 12376 已随上个会话自然消亡，9000 由新网关持有。

## 🔜 M2 后续路线

- M2.2：OpenFeign 拆链完善 + Sentinel 替换自研限流 + Seata AT 试点（下单跨服务扣库存）
- M2.3：ES 8 商品搜索全链路（IK 分词/MQ 同步/检索聚合建议）
- M2.4：微服务化后压测对比报告（对齐 M1 基线：同步 QPS 167.6 / 秒杀 P99 80ms）
