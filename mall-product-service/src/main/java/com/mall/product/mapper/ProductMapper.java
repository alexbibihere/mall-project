package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 乐观锁扣库存：WHERE 带 num 条件是防超卖的关键——
     * 并发下只有库存足够的扣减会生效，返回影响行数=0 即代表库存不足。
     */
    @Update("UPDATE product SET stock = stock - #{num} WHERE id = #{id} AND stock >= #{num}")
    int deductStock(@Param("id") Long id, @Param("num") int num);

    /** 回补库存（取消/关单）。 */
    @Update("UPDATE product SET stock = stock + #{num} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("num") int num);
}
