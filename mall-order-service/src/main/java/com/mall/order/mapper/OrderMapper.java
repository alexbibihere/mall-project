package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 状态机 CAS 流转：只有当前状态匹配才更新，天然防并发跳变 + 幂等重放。 */
    @Update("UPDATE orders SET status = #{to}, paid_at = IF(#{to} = 'PAID', NOW(), paid_at) " +
            "WHERE order_no = #{orderNo} AND status = #{from}")
    int casStatus(@Param("orderNo") String orderNo, @Param("from") String from, @Param("to") String to);
}
