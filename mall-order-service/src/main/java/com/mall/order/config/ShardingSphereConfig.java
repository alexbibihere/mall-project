package com.mall.order.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.keygen.KeyGenerateStrategyConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * M3.2 ShardingSphere 编程式配置（绕开 SS YAML 引擎与 Boot snakeyaml 2.x 的版本冲突）。
 *
 * 分片设计（基因法）：
 * - 库：ds0=mall, ds1=mall_shard1；表：orders/order_item/order_status_log 各 _0/_1
 * - orders/order_item 分片键 user_id：库 user_id%2、表 user_id%2
 * - order_status_log 分片键 order_no 基因（末位 = user_id%10）：末位%2 路由，
 *   与 user_id%2 恒等（偶偶奇奇），buyer 维度与订单号维度天然同片
 * - 其余表（payment/order_stock_task/...）走默认数据源 ds0 不分片
 */
@Configuration
public class ShardingSphereConfig {

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int poolSize;

    private DataSource baseDs(String name, String url) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(poolSize);
        ds.setPoolName(name);
        return ds;
    }

    @Bean
    public DataSource dataSource() throws SQLException {
        Map<String, DataSource> dsMap = new HashMap<>();
        dsMap.put("ds0", baseDs("ds0",
                "jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"));
        dsMap.put("ds1", baseDs("ds1",
                "jdbc:mysql://localhost:3306/mall_shard1?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"));

        ShardingRuleConfiguration rule = new ShardingRuleConfiguration();

        // orders：user_id 分库分表
        ShardingTableRuleConfiguration orders = new ShardingTableRuleConfiguration("orders", "ds${0..1}.orders_${0..1}");
        orders.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "db-mod"));
        orders.setTableShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "tbl-mod-orders"));
        orders.setKeyGenerateStrategy(new KeyGenerateStrategyConfiguration("id", "snowflake"));
        rule.getTables().add(orders);

        // order_item：跟随订单的 user_id（经 OrderService 冗余到行）
        ShardingTableRuleConfiguration items = new ShardingTableRuleConfiguration("order_item", "ds${0..1}.order_item_${0..1}");
        items.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "db-mod"));
        items.setTableShardingStrategy(new StandardShardingStrategyConfiguration("user_id", "tbl-mod-item"));
        rule.getTables().add(items);

        // order_status_log：订单号基因路由
        ShardingTableRuleConfiguration logs = new ShardingTableRuleConfiguration("order_status_log", "ds${0..1}.order_status_log_${0..1}");
        logs.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("order_no", "gene-db"));
        logs.setTableShardingStrategy(new StandardShardingStrategyConfiguration("order_no", "gene-tbl"));
        rule.getTables().add(logs);

        // 5.5.x 单表校验：未分片的表显式注册到默认数据源（否则 TableNotFoundException）
        org.apache.shardingsphere.single.config.SingleRuleConfiguration single =
                new org.apache.shardingsphere.single.config.SingleRuleConfiguration(
                        java.util.List.of("ds0.payment", "ds0.order_stock_task", "ds0.users", "ds0.address",
                                "ds0.product", "ds0.cart_item", "ds0.undo_log"),
                        "ds0");

        // 行表达式算法
        rule.getShardingAlgorithms().put("db-mod", inlineAlg("ds${user_id % 2}"));
        rule.getShardingAlgorithms().put("tbl-mod-orders", inlineAlg("orders_${user_id % 2}"));
        rule.getShardingAlgorithms().put("tbl-mod-item", inlineAlg("order_item_${user_id % 2}"));
        rule.getShardingAlgorithms().put("gene-db", inlineAlg("ds${new Integer(order_no.substring(order_no.length() - 1)) % 2}"));
        rule.getShardingAlgorithms().put("gene-tbl", inlineAlg("order_status_log_${new Integer(order_no.substring(order_no.length() - 1)) % 2}"));

        // 主键生成：雪花
        rule.getKeyGenerators().put("snowflake", new AlgorithmConfiguration("SNOWFLAKE", new Properties()));

        Properties props = new Properties();
        props.setProperty("sql-show", "false");
        return ShardingSphereDataSourceFactory.createDataSource(dsMap, java.util.List.of(rule, single), props);
    }

    private AlgorithmConfiguration inlineAlg(String expression) {
        Properties p = new Properties();
        p.setProperty("algorithm-expression", expression);
        return new AlgorithmConfiguration("INLINE", p);
    }
}
