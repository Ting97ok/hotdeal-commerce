# 핫딜(hotdeal) API 설계 문서

## 개요

핫딜(hotdeal)은 한정 수량을 특가로 정해진 기간에만 파는 선착순 판매다. 상품 하나에 여러 회차가 붙을 수 있다(상품:핫딜 = 1:N). 관리자용 API(Admin)와 사용자용 API(User)를 분리해 설계한다.

- **현재 범위**: 핫딜 등록(관리자) · 핫딜 단건 조회(공개) — 슬라이스 0(slice 0, 한 번에 끝까지 통과시키는 세로 작업 단위, 보통 API 하나)
- **이 범위의 설계 제약**: 수정·수량 변경 API를 두지 않는다 — `totalQuantity`(총 한정 수량)는 등록 후 불변이며, 변경은 "취소 후 재등록"으로 처리한다([ADR-0007 결정3](../adr/0007-hotdeal-state-operations.md)).
- **문서 구조**: Admin API → [api-design-admin.md](api-design-admin.md) / User API → [api-design-user.md](api-design-user.md)
- **단계 추적**: 단계가 늘어도 파일을 추가하지 않고 이 문서를 고도화하며, 변경은 아래 변경 이력에 한 줄씩 남긴다.

---

## 변경 이력

| 버전 | 일자 | 내용 |
|------|------|------|
| v0.1 | 2026-06-15 | 등록·조회 2개 API 설계 초안 (슬라이스 0) |

---

## 공통 정의

### 엔티티 구조

핫딜 등록 시 `HotDeal`과 `HotDealStock`(핫딜 예약 재고)이 1:1로 함께 생성되고, 대상 상품의 재고 원본 `ProductStock`에서 그 수량만큼 예약을 차감한다([재고 동시성 ADR](../adr/concurrency.md)).

#### HotDeal (핫딜)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | 핫딜 고유 ID (BaseEntity) |
| dealPrice | BigDecimal | 특가 (필수, `DECIMAL(12,0)` — 소수 자리 0) |
| totalQuantity | int | 총 한정 수량 (필수, 1 이상, 등록 후 불변) |
| startAt | LocalDateTime | 판매 시작 시각 (필수) |
| endAt | LocalDateTime | 판매 종료 시각 (필수) |
| status | HotDealStatus | 상태 (필수, 등록 시 `ACTIVE` 고정) |
| canceledAt | LocalDateTime | 긴급 중단 시각 (취소 시에만 기록, 현재 범위 미사용) |
| product | Product | 대상 상품 (ManyToOne, LAZY, 논리 참조 — DB FK 제약 없음) |
| createdAt | LocalDateTime | 생성일시 (BaseEntity) |
| updatedAt | LocalDateTime | 수정일시 (BaseEntity) |

#### HotDealStock (핫딜 예약 재고)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | 재고 고유 ID (BaseEntity) |
| hotDealId | Long | 핫딜 ID (논리 참조, 1:1, UNIQUE — JPA 연관 아닌 raw 값) |
| remainingQuantity | int | 잔여 수량 (필수, 0 이상, 등록 시 `= totalQuantity`) — 차감은 원자적 조건부 UPDATE (2026-07 갱신, 구 명세의 version 낙관락은 [재고 동시성 ADR](../adr/concurrency.md)로 제거) |
| createdAt | LocalDateTime | 생성일시 (BaseEntity) |
| updatedAt | LocalDateTime | 수정일시 (BaseEntity) |

> **잔여 수량(remainingQuantity)**: 핫딜에 남은 구매 가능 수량. 별도 `HotDealStock` 행이 1:1로 보유하며, 등록 시점에는 총 한정 수량과 같다.

#### ProductStock (상품 재고 원본)

상품별 실물·예약 재고의 원본이다. 핫딜 등록이 이 행에서 예약을 차감하고(가용 검사), 결제 확정이 실물·예약을 함께 줄인다([재고 동시성 ADR](../adr/concurrency.md)).

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | 재고 고유 ID (BaseEntity) |
| productId | Long | 상품 ID (논리 참조, 1:1, UNIQUE — raw 값) |
| onHandQuantity | int | 실물 수량 (창고 실재 수, 0 이상) |
| reservedQuantity | int | 예약 수량 (핫딜에 떼어 둔 수, 0 이상, ≤ 실물) — 예약·차감은 원자적 조건부 UPDATE ([재고 동시성 ADR 4절](../adr/concurrency.md), version 없음) |
| createdAt | LocalDateTime | 생성일시 (BaseEntity) |
| updatedAt | LocalDateTime | 수정일시 (BaseEntity) |

> **가용 수량**: `onHandQuantity − reservedQuantity` (저장하지 않고 계산). 핫딜 등록 시 "가용 ≥ 총 한정 수량"이어야 예약이 가능하다. 초기 `onHandQuantity`는 시드/픽스처로 넣는다(운영 입고 API는 스코프 밖).

#### Product (상품)

핫딜이 `productId`로 참조하는 대상이다. 등록 시 존재 검증과 특가-정가 비교(특가 < 정가)의 대상이고, 그 재고 원본인 `ProductStock`의 가용 수량이 총 한정 수량 이상인지 검사·예약하는 대상이다.

---

### Enum 정의

#### HotDealStatus (핫딜 상태)

| 값 | 설명 |
|----|------|
| ACTIVE | 진행 (관리자 취소 전 기본 상태) |
| CANCELED | 관리자 취소 (긴급 중단) |

> 진행 중/매진 같은 파생 상태는 별도 상태값으로 두지 않는다. `status`는 관리자 취소 여부만 나타내는 표시값이며, 진행 단계는 클라이언트가 `startAt`/`endAt`/`remainingQuantity`로 판단한다([ADR-0007 결정1](../adr/0007-hotdeal-state-operations.md)).

---

### 공통 응답 형식

#### 성공

```json
{
  "result": true,
  "data": {
    "...": "..."
  }
}
```

#### 실패

```json
{
  "result": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "에러 메시지"
  }
}
```

---

### 핫딜 ExceptionCode

핫딜 도메인이 던지는 예외 코드다. 실패 응답의 `error.code`는 enum 이름과 같다.

| ExceptionCode | 소속 enum | HttpStatus | 발생 |
|---------------|-----------|:----------:|------|
| HOTDEAL_NOT_FOUND | HotDealExceptionCode | 404 | 조회 — id에 해당하는 핫딜 없음 (취소·종료된 핫딜 포함) |
| HOTDEAL_PERIOD_OVERLAP | HotDealExceptionCode | 409 | 등록 — 같은 상품의 ACTIVE 핫딜과 판매 기간 겹침 |
| INVALID_HOTDEAL_PERIOD | HotDealExceptionCode | 400 | 등록 — `startAt >= endAt` (엔티티 `create()` 도메인 검증) |
| INVALID_DEAL_PRICE | HotDealExceptionCode | 400 | 등록 — 특가가 정가 이상 (`dealPrice >= product.price`, 엔티티 `create()`) |
| PRODUCT_NOT_FOUND | ProductExceptionCode | 404 | 등록 — productId에 해당하는 상품 없음 |
| INSUFFICIENT_PRODUCT_STOCK | ProductExceptionCode | 409 | 등록 — 상품 가용 재고(실물−예약) < 총 한정 수량 (예약 불가) |

---

### 알려진 제약 / 전제

| 항목 | 내용 | 근거 |
|------|------|------|
| DB FK 제약 없음 | `hot_deals.product_id`·`stock.hot_deal_id`는 논리 참조다. DB가 부모 존재를 보장하지 않으므로 서비스의 존재 검증이 책임진다. | [ADR-0003](../adr/0003-no-db-fk-constraints.md) |
| 재고는 객체 연관이 아님 | `HotDealStock.hotDealId`·`ProductStock.productId`는 raw `Long`(JPA 연관 아님). 재고는 상위 엔티티와 독립적으로 차감되는 행이라 객체 연관 없이 두는 단순 구현 선택이다(성능 결정이 아니며 `@OneToOne` 단건 조회·Redis 교체는 근거로 들지 않는다). 등록은 HotDeal save→PK 확보 후 그 id로 HotDealStock 생성, 조회 잔여 수량은 `HotDealStockRepository`로 별도 조회. | [erd 7장](../design/erd.md) · [entity.md](../../.claude/rules/entity.md) |
| 상품 재고 예약(등록) | 등록은 `ProductStock` 가용(실물−예약)이 총 한정 수량 이상인지 검사 후 예약을 그만큼 늘린다. 부족하면 `INSUFFICIENT_PRODUCT_STOCK`(409). 가용 검사·예약·HotDeal·HotDealStock 생성은 한 트랜잭션(부분 생성·초과 예약 방지). 동시 예약 경합은 원자적 조건부 UPDATE가 직렬화([재고 동시성 ADR 4절](../adr/concurrency.md) — 구 명세의 낙관락은 폐기, admin 문서 반영 노트 참조). | [재고 동시성 ADR](../adr/concurrency.md) |
| 기간 배타 제약 부재 | MySQL은 기간 겹침을 DB 제약으로 표현할 수 없어, 겹침 금지는 등록 API의 서비스 검증으로 막는다. | [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md) · [ADR-0007 결정4](../adr/0007-hotdeal-state-operations.md) |
| 공개 조회 인증 | 조회는 공개이므로 `SecurityConfig`의 `permitAll`에 `GET /api/hotdeals/**`를 추가한다. | — |
| DB CHECK 최후 방어 | 서비스 검증이 뚫려도 데이터 오염을 막는 안전망 — `ck_hot_deals_period`(start_at < end_at) · `ck_hot_deals_total_quantity`(> 0) · `ck_hot_deal_stock_remaining`(remaining_quantity >= 0) · `ck_product_stock_reserved`(reserved_quantity <= on_hand_quantity). | [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md) |
