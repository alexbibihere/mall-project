-- M3.0: 订单状态流水表（状态机审计，只增不改）
CREATE TABLE IF NOT EXISTS `order_status_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `order_no`    VARCHAR(64)  NOT NULL COMMENT '订单号',
  `from_status` VARCHAR(32)  NOT NULL COMMENT '流转前状态',
  `to_status`   VARCHAR(32)  NOT NULL COMMENT '流转后状态',
  `event`       VARCHAR(32)  NOT NULL COMMENT '触发事件: USER_CANCEL/PAY_NOTIFY/TIMEOUT_REAPER/SECKILL_ASYNC',
  `operator`    VARCHAR(64)  NOT NULL DEFAULT 'system' COMMENT '操作者: userId 或 system',
  `created_at`  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单状态流水';
