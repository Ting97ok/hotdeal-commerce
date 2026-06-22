# 주문(order) API 설계 문서

## 개요

주문(order)은 회원이 활성 핫딜의 상품을 특가로 사는 구매 행위다. 클라이언트는 핫딜 ID를 모른 채 상품만 지정하고, 서버가 그 상품의 활성 핫딜을 찾아(해소) 핫딜 예약 재고를 수량만큼 차감한 뒤 결제 대기(PENDING) 주문을 만든다.

- **현재 범위**: 핫딜 구매(회원) — 슬라이스 1(slice 1, 한 번에 끝까지 통과시키는 세로 작업 단위, API 하나)
- **이 범위의 설계 제약**: 결제 승인·PAID 확정·만료 sweep·취소는 두지 않는다(슬라이스 2·3). 주문은 **PENDING 생성까지**이며 payment 도메인을 건드리지 않는다.
- **문서 구조**: User API → [api-design-user.md](api-design-user.md)
- **단계 추적**: 단계가 늘어도 파일을 추가하지 않고 이 문서를 고도화하며, 변경은 아래 변경 이력에 한 줄씩 남긴다.

---

## 변경 이력

| 버전 | 일자 | 내용 |
|------|------|------|
| v0.1 | 2026-06-22 | 핫딜 구매 1개 API 설계 초안 (슬라이스 1) |

---

## 공통 정의

### 엔티티 구조

구매는 `Order`(주문)를 만들고, 대상 핫딜의 `HotDealStock`(핫딜 예약 재고) 잔여를 그 수량만큼 차감한다([ADR-0011](../adr/0011-product-inventory-reservation.md) 결정3·4). 주문은 주문자·핫딜·상품을 논리 참조하고, 특가·수량을 주문 시점에 스냅샷한다. 엔티티·`orders`/`hot_deal_stock` 스키마는 이미 완비돼 슬라이스1에서 **마이그레이션 변경이 없다**.

#### Order (주문)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | 주문 고유 ID (BaseEntity) |
| orderNo | String | 주문 번호 (필수, UUID, `CHAR(36)` UNIQUE — 토스 orderId 겸용) |
| quantity | int | 주문 수량 (필수, 1 이상, ≤ 핫딜 `maxPerOrder`) |
| orderAmount | BigDecimal | 주문 금액 (필수, `DECIMAL(12,0)` — 특가×수량 주문 시점 저장) |
| status | OrderStatus | 주문 상태 (필수, 등록 시 `PENDING` 고정) |
| cancelReason | CancelReason | 취소 사유 (취소 시에만, 현재 범위 미사용) |
| expiresAt | LocalDateTime | 미결제 만료 시각 (필수, 주문시각 + 결제제한시간) |
| user | User | 주문자 (ManyToOne, LAZY, 논리 참조) |
| hotDeal | HotDeal | 대상 핫딜 (ManyToOne, LAZY, 논리 참조 — 서버가 해소) |
| product | Product | 산 상품 (ManyToOne, LAZY, 논리 참조 — 요청 productId) |
| createdAt | LocalDateTime | 생성일시 (BaseEntity) |
| updatedAt | LocalDateTime | 수정일시 (BaseEntity) |

> **is_active (생성 칼럼, 엔티티 비매핑)**: `orders`에 `is_active = IF(status IN ('PENDING','PAID'), 1, NULL)` 저장 생성 칼럼이 있고 `uk_orders_active(user_id, hot_deal_id, is_active)` 유니크가 걸려 있다 — 계정당 같은 핫딜에 살아 있는 주문 1건만 허용([ADR-0005 결정1](../adr/0005-one-per-user-active-unique.md)). JPA가 생성 칼럼에 쓰기를 시도하는 함정을 피하려 엔티티에 매핑하지 않는다(DDL 전용).

#### HotDealStock (핫딜 예약 재고) — 차감 대상

| 필드 | 타입 | 설명 |
|------|------|------|
| hotDealId | Long | 핫딜 ID (논리 참조, 1:1, UNIQUE) |
| remainingQuantity | int | 잔여 수량 (0 이상, 구매가 차감) |
| version | Long | 낙관락 버전 (`@Version` — 구매 차감 경합 제어) |

> **차감 동시성 = 낙관락(@Version)**: 구매는 `findByHotDealId`로 재고 행을 PC(JPA가 객체를 추적하는 메모리 공간)에 올려 version을 추적하고, 엔티티 `deduct(quantity)`로 잔여를 줄인 뒤, 트랜잭션 커밋 flush에서 version을 비교한다. 충돌 시 `ObjectOptimisticLockingFailureException` → `PURCHASE_CONFLICT`(409), 서버 재시도는 하지 않는다([ADR-0006](../adr/0006-correctness-invariants-defense-layers.md)). 핫딜 등록의 `ProductStock` 차감(원자적 조건부 UPDATE)과 **메커니즘이 다르다** — 낙관락은 [ADR-0009](../adr/0009-stock-concurrency-design.md) 5방식 벤치마크의 출발점이다.

---

### Enum 정의

#### OrderStatus (주문 상태)

| 값 | 설명 |
|----|------|
| PENDING | 결제 대기 (재고 선점 완료, 미결제) — 슬라이스1 생성 상태 |
| PAID | 결제 완료 (슬라이스3) |
| CANCELED | 취소 (결제 실패·만료, 슬라이스2·3) |

> 슬라이스1은 `PENDING`만 사용한다. `CancelReason`(PAYMENT_FAILED·EXPIRED)은 슬라이스2·3 범위.

---

### 공통 응답 형식

#### 성공

```json
{ "result": true, "data": { "...": "..." } }
```

#### 실패

```json
{ "result": false, "error": { "code": "ERROR_CODE", "message": "에러 메시지" } }
```

전역 `ApiResponseAdvice`가 raw DTO를 `ApiResponse`로 감싼다. 실패는 `GlobalExceptionHandler`가 `DomainException`의 HttpStatus로 처리.

---

### 주문 ExceptionCode

실패 응답의 `error.code`는 enum 이름과 같다. 구매 흐름의 거절 사유는 order, 재고 부족·정보 없음은 stock, 주문자·상품 부재는 각 도메인 소속이다.

| ExceptionCode | 소속 enum | HttpStatus | 발생 |
|---------------|-----------|:----------:|------|
| NO_ACTIVE_DEAL | OrderExceptionCode | 404 | 구매 — 상품에 현재 활성(판매기간 내 ACTIVE) 핫딜 없음 |
| ALREADY_PURCHASED | OrderExceptionCode | 409 | 구매 — 같은 핫딜에 이미 살아 있는 주문 있음(계정당 1활성) |
| EXCEEDS_PURCHASE_LIMIT | OrderExceptionCode | 400 | 구매 — `quantity > 핫딜 maxPerOrder` |
| PURCHASE_CONFLICT | OrderExceptionCode | 409 | 구매 — 재고 차감 낙관락 경합(동시 차감 충돌) |
| SOLD_OUT | StockExceptionCode | 409 | 구매 — 핫딜 잔여 수량 < `quantity` |
| STOCK_NOT_FOUND | StockExceptionCode | 404 | 구매 — 핫딜 재고 정보 없음(정상 운영 시 활성 핫딜엔 항상 존재, 방어) |
| PRODUCT_NOT_FOUND | ProductExceptionCode | 404 | 구매 — productId 상품 없음 |
| USER_NOT_FOUND | UserExceptionCode | 404 | 구매 — 주문자 없음(인증됐으나 탈퇴 등) |

---

### 알려진 제약 / 전제

| 항목 | 내용 | 근거 |
|------|------|------|
| 1인 1활성주문 | `uk_orders_active`(생성 칼럼)가 계정당 살아 있는 주문 1건을 강제. 사전 가드(existsActiveOrder)로 친절 거절 + 유니크로 동시 중복 최종 차단(방어 분업). | [ADR-0005](../adr/0005-one-per-user-active-unique.md) · [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md) |
| 주문→재고 순서 | 한 트랜잭션에서 주문 INSERT를 재고 차감 UPDATE보다 먼저. Hibernate 기본 flush 순서와 일치(우회 불필요). | [ADR-0009](../adr/0009-stock-concurrency-design.md) 결정4 |
| 만료시각 외부화 | `expiresAt = 주문시각 + order.payment-timeout`(application.yml, 임시 `PT10M`). 슬라이스2에서 최종값 확정. | [ADR-0004](../adr/0004-stock-reservation-lifecycle.md) |
| 결제 범위 밖 | 결제 승인·PAID·만료·취소는 슬라이스2·3. 슬라이스1은 PENDING 생성까지. payment 미접촉. | — |
| 구매 인증 | 회원 전용. `SecurityConfig`의 `anyRequest().authenticated()`가 커버(비회원 401) — 별도 규칙 불필요. | — |
| 마이그레이션 변경 없음 | `orders`·`hot_deal_stock` 스키마가 슬라이스1 필요분 완비(작업1 ADR-0011 반영). 새 V 파일·기존 V 수정 모두 없음. | [ADR-0011](../adr/0011-product-inventory-reservation.md) |
| DB CHECK 최후 방어 | 서비스 검증이 뚫려도 데이터 오염을 막는 백스톱 — `ck_orders_quantity`(>=1) · `ck_orders_order_amount`(>=0) · `ck_hot_deal_stock_remaining`(remaining_quantity >= 0). | [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md) |
