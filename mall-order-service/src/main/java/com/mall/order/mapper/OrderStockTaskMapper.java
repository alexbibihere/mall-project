package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.OrderStockTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderStockTaskMapper extends BaseMapper<OrderStockTask> {

    /** CAS 抢任务：INIT/FAILED -> PROCESSING，只有抢到的消费者才执行扣减（幂等核心）。 */
    @Update("UPDATE order_stock_task SET status = 'PROCESSING', retries = retries + 1, updated_at = NOW(6) " +
            "WHERE id = #{id} AND status IN ('INIT','FAILED')")
    int casGrab(@Param("id") Long id);
}
