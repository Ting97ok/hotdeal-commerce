CREATE TABLE payments (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    order_id        BIGINT         NOT NULL COMMENT '주문 (논리 참조, 1:N)',
    amount          DECIMAL(12, 0) NOT NULL COMMENT '결제 금액',
    status          VARCHAR(20)    NOT NULL COMMENT '결제 상태',
    pg_payment_key  VARCHAR(200)   NULL COMMENT 'PG 거래 키',
    approved_at     DATETIME(6)    NULL COMMENT '승인 시각',
    created_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_pg_payment_key (pg_payment_key),
    KEY idx_payments_order_id (order_id),
    KEY idx_payments_status_created_at (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
