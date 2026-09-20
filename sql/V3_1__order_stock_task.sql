-- M3.1: 库存扣减本地消息表（下单链路最终一致）
-- 本地事务与订单同库同事务写入，事务提交后发 MQ；发送失败/消费失败由补偿任务重扫。
-- 幂等：消费端 CAS 抢任务（INIT/FAILED -> PROCESSING），天然防重复扣减。
CREATE TABLE IF NOT EXISTS `order_stock_task` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `order_no`    VARCHAR(64)  NOT NULL COMMENT '订单号',
  `product_id`  BIGINT       NOT NULL,
  `quantity`    INT          NOT NULL,
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/PROCESSING/DONE/FAILED',
  `retries`     INT          NOT NULL DEFAULT 0,
  `fail_reason` VARCHAR(255) NULL,
  `created_at`  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at`  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存扣减本地消息表';
