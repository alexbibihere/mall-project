package com.mall.cart.service;

import com.mall.common.BizException;
import com.mall.common.UserContext;
import com.mall.product.entity.Product;
import com.mall.product.service.ProductService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 购物车：Redis Hash 存储（key=cart:{userId}, field=productId, value=qty|checked），不落库。
 * 高并发要点：加购/改数量全是单 key 哈希操作，无 DB 参与，天然扛写。
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private static final int MAX_KINDS = 120;       // 单用户最多 120 种 SKU
    private static final int MAX_QTY_PER_ITEM = 99; // 单品上限

    private final StringRedisTemplate redis;
    private final ProductService productService;

    private String key() {
        return "cart:" + UserContext.get();
    }

    public void add(Long productId, int qty) {
        String key = key();
        String field = String.valueOf(productId);
        Product p = productService.detail(productId); // 顺带校验商品存在
        String existing = redis.<String, String>opsForHash().get(key, field);
        int newQty = (existing == null ? 0 : parse(existing)[0]) + qty;
        if (newQty > MAX_QTY_PER_ITEM) {
            throw BizException.of("单品最多购买" + MAX_QTY_PER_ITEM + "件");
        }
        if (existing == null) {
            Long kinds = redis.opsForHash().size(key);
            if (kinds >= MAX_KINDS) {
                throw BizException.of("购物车最多存放" + MAX_KINDS + "种商品");
            }
        }
        redis.<String, String>opsForHash().put(key, field, newQty + "|1");
    }

    public void updateQty(Long productId, int qty) {
        if (qty <= 0 || qty > MAX_QTY_PER_ITEM) {
            throw BizException.of("数量非法");
        }
        String v = redis.<String, String>opsForHash().get(key(), String.valueOf(productId));
        if (v == null) {
            throw BizException.of("购物车中没有该商品");
        }
        redis.<String, String>opsForHash().put(key(), String.valueOf(productId), qty + "|" + parse(v)[1]);
    }

    public void check(Long productId, boolean checked) {
        String v = redis.<String, String>opsForHash().get(key(), String.valueOf(productId));
        if (v == null) {
            throw BizException.of("购物车中没有该商品");
        }
        redis.<String, String>opsForHash().put(key(), String.valueOf(productId), parse(v)[0] + "|" + (checked ? 1 : 0));
    }

    public void remove(Long productId) {
        redis.opsForHash().delete(key(), String.valueOf(productId));
    }

    /** 列表：合并商品实时信息；商品失效/改价在视图层标记（按功能清单 4.1）。 */
    public List<CartItemView> list() {
        Map<String, String> entries = redis.<String, String>opsForHash().entries(key());
        List<CartItemView> views = new ArrayList<>();
        for (var e : entries.entrySet()) {
            int[] parts = parse(e.getValue());
            CartItemView v = new CartItemView();
            v.setProductId(Long.valueOf(e.getKey()));
            v.setQuantity(parts[0]);
            v.setChecked(parts[1] == 1);
            try {
                Product p = productService.detail(v.getProductId());
                v.setName(p.getName());
                v.setPrice(p.getPrice());
            } catch (BizException ex) {
                v.setName("【已失效】");
            }
            views.add(v);
        }
        return views;
    }

    private int[] parse(String value) {
        String[] parts = value.split("\\|");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    @Data
    public static class CartItemView {
        private Long productId;
        private String name;
        private java.math.BigDecimal price;
        private int quantity;
        private boolean checked;
    }
}
