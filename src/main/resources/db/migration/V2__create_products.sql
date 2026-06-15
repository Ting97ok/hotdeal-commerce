CREATE TABLE products (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100)   NOT NULL COMMENT '상품명',
    description VARCHAR(1000)  NULL COMMENT '상품 설명',
    price       DECIMAL(12, 0) NOT NULL COMMENT '정가',
    status      VARCHAR(20)    NOT NULL COMMENT '판매 상태',
    created_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
