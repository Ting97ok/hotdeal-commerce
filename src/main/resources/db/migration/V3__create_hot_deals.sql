CREATE TABLE hot_deals (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    product_id     BIGINT         NOT NULL COMMENT '대상 상품 (논리 참조)',
    deal_price     DECIMAL(12, 0) NOT NULL COMMENT '특가',
    total_quantity INT            NOT NULL COMMENT '총 한정 수량 (등록 후 불변)',
    max_per_order  INT            NOT NULL COMMENT '1주문 최대 수량',
    start_at       DATETIME(6)    NOT NULL COMMENT '판매 시작 시각',
    end_at         DATETIME(6)    NOT NULL COMMENT '판매 종료 시각',
    status         VARCHAR(20)    NOT NULL COMMENT '상태 (ACTIVE/CANCELED)',
    canceled_at    DATETIME(6)    NULL COMMENT '긴급 중단 시각 (취소 시만)',
    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_hot_deals_product_id (product_id),
    CONSTRAINT ck_hot_deals_total_quantity CHECK (total_quantity > 0),
    CONSTRAINT ck_hot_deals_max_per_order CHECK (max_per_order > 0),
    CONSTRAINT ck_hot_deals_period CHECK (start_at < end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
