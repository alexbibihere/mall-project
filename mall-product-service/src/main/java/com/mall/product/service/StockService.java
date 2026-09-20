package com.mall.product.service;

import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 库存服务：M1 = DB 乐观锁扣减（防超卖基线实现）。
 * M2.3: 扣减/回补后发商品变更事件（MQ -> search 同步 ES 的库存字段）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductMapper productMapper;
    private final ProductService productService;

    /** 扣减：乐观锁，失败即库存不足。 */
    @Transactional
    public void deduct(Long productId, int num) {
        if (!deductQuiet(productId, num)) {
            throw com.mall.common.BizException.of(409, "库存不足");
        }
    }

    /** 静默扣减：返回 false=库存不足（供 MQ 消费端分支处理，不抛异常中断事务）。 */
    @Transactional
    public boolean deductQuiet(Long productId, int num) {
        int affected = productMapper.deductStock(productId, num);
        if (affected > 0) {
            Product p = productMapper.selectById(productId);
            if (p != null) {
                afterCommit(p); // 事务提交后再失效缓存/发事件，防 search 拿到旧值
            }
            return true;
        }
        return false;
    }

    /** 回补：取消/超时关单场景，幂等性由调用方（订单状态机）保证。 */
    @Transactional
    public void restore(Long productId, int num) {
        productMapper.restoreStock(productId, num);
        Product p = productMapper.selectById(productId);
        if (p != null) {
            afterCommit(p);
        }
    }

    /** 缓存失效 + 变更事件必须等本事务提交后执行（提交前发事件会让 ES 拿到旧值）。 */
    private void afterCommit(Product p) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productService.evictAndPublish(p);
            }
        });
    }

    public Product getProduct(Long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            throw com.mall.common.BizException.of("商品不存在");
        }
        return p;
    }
}
