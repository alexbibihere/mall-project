-- M3.2: order_item 冗余 user_id 分片键（跟随订单）
ALTER TABLE `order_item` ADD COLUMN `user_id` BIGINT NULL AFTER `order_id`;
