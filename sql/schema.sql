-- ============================================================
-- 亿级流量商城 M1 建表脚本（幂等，可重复执行）
-- 库：mall（utf8mb4）
-- 说明：M1 单体单库；M3 演进时 orders/order_item 将拆分 16 库 x 64 表
-- ============================================================
USE mall;

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  phone         VARCHAR(20)     NOT NULL COMMENT '手机号（登录账号）',
  password_hash CHAR(64)        NOT NULL COMMENT 'SHA-256 摘要（生产应换 BCrypt）',
  nick_name     VARCHAR(64)     NULL,
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户';

CREATE TABLE IF NOT EXISTS address (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  province   VARCHAR(32)     NOT NULL,
  city       VARCHAR(32)     NOT NULL,
  detail     VARCHAR(255)    NOT NULL,
  receiver   VARCHAR(64)     NOT NULL,
  phone      VARCHAR(20)     NOT NULL,
  is_default TINYINT         NOT NULL DEFAULT 0 COMMENT '默认地址唯一（服务层维护）',
  deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收货地址';

CREATE TABLE IF NOT EXISTS product (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name       VARCHAR(255)    NOT NULL,
  category   VARCHAR(64)     NOT NULL,
  brand      VARCHAR(64)     NOT NULL,
  price      DECIMAL(10, 2)  NOT NULL,
  stock      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '可售库存（扣减走乐观锁）',
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商品（M1 中商品即 SKU）';

-- 订单主表：状态机 UNPAID -> PAID -> CANCELLED
CREATE TABLE IF NOT EXISTS orders (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id          BIGINT UNSIGNED NOT NULL,
  order_no         VARCHAR(32)     NOT NULL COMMENT '业务订单号（雪花，对外唯一标识）',
  address_snapshot VARCHAR(512)    NOT NULL COMMENT '下单时地址快照（防后续改地址影响历史单）',
  total_amount     DECIMAL(12, 2)  NOT NULL,
  pay_amount       DECIMAL(12, 2)  NOT NULL,
  status           VARCHAR(16)     NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID/PAID/CANCELLED',
  created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at          DATETIME        NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单主表';

-- 订单明细：商品快照（名称/单价下单时固化）
CREATE TABLE IF NOT EXISTS order_item (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id   BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  name       VARCHAR(255)    NOT NULL COMMENT '商品名快照',
  price      DECIMAL(10, 2)  NOT NULL COMMENT '成交单价快照',
  quantity   INT UNSIGNED    NOT NULL,
  subtotal   DECIMAL(12, 2)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细';

-- 支付单：幂等回调依赖 status 独占锁更新
CREATE TABLE IF NOT EXISTS payment (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id      BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  amount        DECIMAL(12, 2)  NOT NULL,
  channel       VARCHAR(16)     NOT NULL COMMENT 'MOCK/ALIPAY/WECHAT',
  status        VARCHAR(16)     NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID/PAID',
  callback_payload VARCHAR(512) NULL COMMENT '回调报文存档（对账依据）',
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at       DATETIME        NULL,
  PRIMARY KEY (id),
  KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付单';
