CREATE TABLE products (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100)   NOT NULL COMMENT '상품명',
    description VARCHAR(1000)  NULL COMMENT '상품 설명',
    price       DECIMAL(12, 0) NOT NULL COMMENT '정가',
    status      VARCHAR(20)    NOT NULL COMMENT '판매 상태',
    created_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE product_stock (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    product_id        BIGINT      NOT NULL COMMENT '상품 ID (논리 참조, 1:1)',
    on_hand_quantity  INT         NOT NULL COMMENT '실물 수량 (창고 실재 수)',
    reserved_quantity INT         NOT NULL DEFAULT 0 COMMENT '예약 수량 (핫딜에 떼어 둔 수)',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_stock_product_id (product_id),
    CONSTRAINT ck_product_stock_on_hand_quantity CHECK (on_hand_quantity >= 0),
    CONSTRAINT ck_product_stock_reserved_quantity CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_product_stock_reserved_le_on_hand CHECK (reserved_quantity <= on_hand_quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
