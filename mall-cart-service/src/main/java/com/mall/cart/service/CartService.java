package com.mall.cart.service;

import com.mall.cart.feign.ProductClient;
import com.mall.common.BizException;
import com.mall.common.Result;
import com.mall.common.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 购物车：Redis Hash 存储（key=cart:{userId}, field=productId, value=qty|checked），不落库。
 * M2：商品实时信息经 Feign 从 mall-product 获取（含缓存）。
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private static final int MAX_KINDS = 120;
    private static final int MAX_QTY_PER_ITEM = 99;

    private final StringRedisTemplate redis;
    private final ProductClient productClient;

    private String key() {
        return "cart:" + UserContext.get();
    }

    public void add(Long productId, int qty) {
        String key = key();
        String field = String.valueOf(productId);
        // Feign 校验商品存在（顺带回源填充商品缓存）
        Result<Map<String, Object>> r = productClient.detail(productId);
        if (r.getCode() != 0) {
            throw BizException.of("商品不存在");
        }
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

    /** 列表：Feign 合并商品实时信息（价格/名称），失效商品标记。 */
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
                Result<Map<String, Object>> r = productClient.detail(v.getProductId());
                if (r.getCode() == 0 && r.getData() != null) {
                    v.setName(String.valueOf(r.getData().get("name")));
                    v.setPrice(new java.math.BigDecimal(String.valueOf(r.getData().get("price"))));
                } else {
                    v.setName("【已失效】");
                }
            } catch (Exception ex) {
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
