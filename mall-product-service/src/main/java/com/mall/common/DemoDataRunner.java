package com.mall.common;

import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 演示数据：首次启动时若无商品则造 3 个 SKU（M1 中商品即 SKU 粒度）。
 */
@Component
@RequiredArgsConstructor
public class DemoDataRunner implements CommandLineRunner {

    private final ProductMapper productMapper;

    @Override
    public void run(String... args) {
        if (productMapper.selectCount(null) > 0) {
            return;
        }
        insert("经典马克杯", "家居", "MallSelf", "29.90", 100000);
        insert("纯棉T恤 基础款", "服饰", "MallSelf", "59.00", 200000);
        insert("无线蓝牙耳机", "数码", "MallSelf", "199.00", 50000);
        // M2.2: 低库存 SKU，供 Seata AT 全局回滚冒烟（第 2 件扣减失败 -> 回滚回补第 1 件）
        insert("Seata演示 低库存SKU", "演示", "MallSelf", "9.90", 1);
    }

    private void insert(String name, String category, String brand, String price, int stock) {
        Product p = new Product();
        p.setName(name);
        p.setCategory(category);
        p.setBrand(brand);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        productMapper.insert(p);
    }
}
