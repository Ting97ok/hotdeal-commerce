CREATE TABLE hot_deal_stock (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    hot_deal_id        BIGINT      NOT NULL COMMENT '핫딜 ID (논리 참조, 1:1)',
    remaining_quantity INT         NOT NULL COMMENT '잔여 수량 (경합 대상)',
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_hot_deal_stock_hot_deal_id (hot_deal_id),
    CONSTRAINT ck_hot_deal_stock_remaining_quantity CHECK (remaining_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
