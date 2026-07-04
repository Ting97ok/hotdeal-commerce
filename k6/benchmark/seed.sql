-- 동시성 벤치마크 시드 — 상품 1 + 활성 핫딜 1 + 재고 2000.
-- 기간은 NOW() 상대라 언제 실행해도 활성(고정 날짜 금지).
INSERT INTO products (id, name, description, price, status)
  VALUES (1, 'bench', '벤치마크 상품', 10000, 'FOR_SALE');

INSERT INTO product_stock (product_id, on_hand_quantity, reserved_quantity)
  VALUES (1, 2000, 2000);

INSERT INTO hot_deals (id, product_id, deal_price, total_quantity, max_per_order, start_at, end_at, status)
  VALUES (1, 1, 5000, 2000, 1, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 'ACTIVE');

INSERT INTO hot_deal_stock (hot_deal_id, remaining_quantity)
  VALUES (1, 2000);
