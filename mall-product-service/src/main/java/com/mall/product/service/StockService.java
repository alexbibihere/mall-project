package com.mall.product.service;

import com.mall.common.BizException;
import com.mall.product.entity.Product;
import com.mall.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存服务：M1 = DB 乐观锁扣减（防超卖基线实现）。
 * M2 演进：Redis Lua 预扣 -> MQ 异步 -> DB 兜底；M4：热点分桶。
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
            throw BizException.of(409, "库存不足");
        }
    }

    /** 静默扣减：返回 false=库存不足（供 MQ 消费端分支处理，不抛异常中断事务）。 */
    @Transactional
    public boolean deductQuiet(Long productId, int num) {
        int affected = productMapper.deductStock(productId, num);
        if (affected > 0) {
            productService.evict(productId); // 库存变了，详情缓存失效
            return true;
        }
        return false;
    }

    /** 回补：取消/超时关单场景，幂等性由调用方（订单状态机）保证。 */
    @Transactional
    public void restore(Long productId, int num) {
        productMapper.restoreStock(productId, num);
        productService.evict(productId);
    }

    public Product getProduct(Long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            throw BizException.of("商品不存在");
        }
        return p;
    }
}
