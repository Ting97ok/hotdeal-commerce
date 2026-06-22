# 주문(order) User API 설계 문서

> 공통 정의(엔티티·Enum·응답 형식·ExceptionCode·제약)는 [api-design.md](api-design.md) 참조.

## API 목록 (1개)

| # | Method | Endpoint | 설명 |
|---|--------|----------|------|
| 1 | POST | `/api/orders` | 핫딜 구매 |

---

## 1. 핫딜 구매

```
POST /api/orders
```

> 회원이 상품 하나를 지정해 그 상품의 활성 핫딜을 특가로 산다. 서버가 활성 핫딜을 해소(클라이언트는 핫딜 ID를 모름)해 핫딜 예약 재고를 수량만큼 차감하고 결제 대기(PENDING) 주문을 만든다. 계정당 한 핫딜에 살아 있는 주문은 1건이며, 한 주문 수량은 핫딜이 정한 `maxPerOrder`까지다.
>
> 인증: 회원(USER/ADMIN 무관, 비회원 401).

**Request**

| 구분 | 파라미터 | 타입 | 필수 | Validation | 설명 |
|------|----------|------|:--:|------------|------|
| Body | productId | Long | O | @NotNull | 살 상품 ID (서버가 이 상품의 활성 핫딜 해소) |
| Body | quantity | Integer | O | @NotNull @Min(1) | 구매 수량 (1 이상, 상한은 핫딜 `maxPerOrder`) |

**Request Body 예시**

```json
{
  "productId": 10,
  "quantity": 2
}
```

**검증**

| 검증 항목 | 방식 | 에러코드 |
|-----------|------|----------|
| productId 필수 | @NotNull | VALIDATION_ERROR |
| quantity 필수·1 이상 | @NotNull @Min(1) | VALIDATION_ERROR |
| 주문자 존재 | 비즈니스 검증 (userService.getById) | USER_NOT_FOUND |
| 상품 존재 | 비즈니스 검증 (productService.getProduct) | PRODUCT_NOT_FOUND |
| 활성 핫딜 존재 | 비즈니스 검증 (commonHotDealService — now ∈ [startAt, endAt) + ACTIVE) | NO_ACTIVE_DEAL |
| 1인 1활성주문 | 비즈니스 사전가드(existsActiveOrder) + DB 유니크(uk_orders_active) | ALREADY_PURCHASED |
| 수량 ≤ maxPerOrder | 비즈니스 검증 (엔티티 `Order.create`) | EXCEEDS_PURCHASE_LIMIT |
| 핫딜 잔여 ≥ quantity | 비즈니스 검증 (엔티티 `HotDealStock.deduct`) | SOLD_OUT |
| 재고 차감 경합 | 비즈니스 검증 (낙관락 version flush) | PURCHASE_CONFLICT |

> **설계 노트 — 활성 핫딜 해소(NO_ACTIVE_DEAL 404)**: 클라이언트는 productId만 보내고 서버가 그 상품의 활성 핫딜(현재시각 ∈ [startAt, endAt) + status=ACTIVE)을 찾는다. 없으면 404 — "지금 이 상품을 핫딜가로 살 대상(활성 핫딜 리소스)이 없음"은 리소스 부재 성격(`PRODUCT_NOT_FOUND`와 동류). `commonHotDealService.getActiveHotDeal`이 직접 던지며(`getProduct`·`getById`처럼 조회 Service가 없으면 자기 도메인 예외), `NO_ACTIVE_DEAL`은 hotdeal 상태의 사실이라 `HotDealExceptionCode` 소속이다(슬라이스0 `HOTDEAL_NOT_FOUND`와 같은 계열). 같은 상품 기간 겹침은 등록이 막으므로([ADR-0007 결정4](../adr/0007-hotdeal-state-operations.md)) 한 시점 활성 핫딜은 0 또는 1건이며, 쿼리는 방어적으로 `startAt DESC` 첫 건을 쓴다(N건이 떠도 최신 1건).
>
> **설계 노트 — 만료시각 외부화**: `expiresAt = 주문시각 + order.payment-timeout`. application.yml에 `order.payment-timeout: PT10M`(`@ConfigurationProperties` `OrderProperties`, 임시 10분 — 최종값 슬라이스2)으로 외부화하고, `Order.create`가 `paymentTimeout`(Duration)을 받아 `now().plus(timeout)`로 계산한다 — orderNo·금액과 함께 엔티티가 주문 생성 규칙을 캡슐화하고, Service는 `OrderProperties`에서 timeout만 전달한다. timeout 값을 엔티티에 하드코딩하지 않고 외부화한 건 고객 고지 제한시간이 이 값을 참조하고 슬라이스2에서 값만 조정하기 위해서다([ADR-0004](../adr/0004-stock-reservation-lifecycle.md)). 테스트는 절대시각 대신 `now()` 상대(예: `expiresAt > createdAt`, `≈ now + 10분` 허용오차)로 단언한다.
>
> **설계 노트 — quantity 다수 허용**: 한 주문 수량은 1 이상이고 상한은 핫딜 `maxPerOrder`다. "계정당 1개"는 **주문 건수 1**(활성 유니크)이지 **수량 1**이 아니다 — 별개 축이다. `quantity > maxPerOrder`면 `EXCEEDS_PURCHASE_LIMIT`(400). `order_amount = dealPrice × quantity`, 핫딜 재고도 `quantity` 단위로 차감([ADR-0005 결정2](../adr/0005-one-per-user-active-unique.md)).
>
> **설계 노트 — 1인 1활성주문 이중 방어**: 사전 가드(existsActiveOrder)로 대부분을 친절히 거절(`ALREADY_PURCHASED` 409)하고, 동시 중복 클릭은 `uk_orders_active` 유니크가 최종 직렬화한다(위반 → `DataIntegrityViolationException` → `ALREADY_PURCHASED`). 가드는 싸고 사유 있는 거절, 유니크는 동시성 최종 차단([ADR-0006](../adr/0006-correctness-invariants-defense-layers.md) 방어 분업). 활성 판정 = `status IN (PENDING, PAID)`.
>
> **설계 노트 — 주문→재고 순서 + 낙관락 차감**: 한 Facade 트랜잭션에서 주문 INSERT(④)를 재고 차감 UPDATE(⑤)보다 먼저 둔다(Hibernate 기본 flush 순서와 일치 — [ADR-0009](../adr/0009-stock-concurrency-design.md) 결정4). 1인1개 유니크 위반이 주문 INSERT에서 먼저 걸려 불필요한 차감을 막는 부가 이점도 있다. 재고 차감은 낙관락(@Version) — `findByHotDealId`로 행을 PC에 올려 `deduct` 후 커밋 flush에서 version 비교, 충돌 시 `PURCHASE_CONFLICT`(409, 재시도 없음). **핫딜 등록의 ProductStock 원자적 조건부 UPDATE와 메커니즘이 다르다**(혼동 주의).
>
> **설계 노트 — 계층 경계**: order Facade가 타 도메인 Service를 조합한다 — `userService`(user)·`productService`(product)·`commonHotDealService`(hotdeal)·`hotDealStockService`(stock). order Service는 자기 `OrderRepository`와 `Order` 도메인 로직만. 활성 핫딜 해소는 "모든 구매가 동일해야 하는 정규 조회"라 hotdeal의 Common 진입 Service에 둔다([service.md](../../.claude/rules/service.md)). 조회 Service가 없으면 자기 도메인 예외를 직접 던지므로(`getProduct`→`PRODUCT_NOT_FOUND`, `getActiveHotDeal`→`NO_ACTIVE_DEAL`) Facade는 `orElseThrow` 없이 결과를 받는다.

**Response**

```json
{
  "result": true,
  "data": {
    "orderId": 1,
    "orderNo": "550e8400-e29b-41d4-a716-446655440000",
    "orderAmount": 19800,
    "expiresAt": "2026-06-22T12:10:00"
  }
}
```

**Response 필드**

| 필드 | 타입 | 설명 | 매핑 |
|------|------|------|------|
| orderId | Long | 주문 ID | `order.id` (MapStruct `@Mapping(source="id", target="orderId")`) |
| orderNo | String | 주문 번호 (결제 연동용, 토스 orderId 겸용) | `order.orderNo` |
| orderAmount | BigDecimal | 주문 금액 (특가×수량) | `order.orderAmount` |
| expiresAt | LocalDateTime | 결제 만료 시각 (고객 고지 제한시간) | `order.expiresAt` |

> status는 항상 `PENDING`이라 응답에서 생략한다(결제 진행에 필요한 식별자·금액·만료만).

**테스트 리스트**

> vertical TDD 사이클로 한 줄씩 누적한다([commit-checkpoint.md](../../.claude/rules/commit-checkpoint.md)). 설계 단계는 헤더만 둔다(placeholder 행 금지).

| # | 테스트 케이스 | 시나리오 | 상태 | 작성일 |
|---|---------------|----------|------|--------|
| 1 | `purchaseHotDeal` | 활성 핫딜 구매 시 주문 PENDING 생성 + 핫딜 재고 차감(낙관락, 98=100−2) | ✅ Pass | 2026-06-22 |
| 2 | `noActiveDeal` | 활성 핫딜 없는 상품 구매 시 NO_ACTIVE_DEAL(404), 주문 미생성 | ✅ Pass | 2026-06-22 |
| 3 | `alreadyPurchased` | 같은 회원이 같은 핫딜 이미 구매 시 ALREADY_PURCHASED(409, 사전가드), 주문 1건 유지 | ✅ Pass | 2026-06-22 |
| 4 | `exceedsPurchaseLimit` | quantity > maxPerOrder 구매 시 EXCEEDS_PURCHASE_LIMIT(400), 주문 미생성 | ✅ Pass | 2026-06-22 |

**구현 로직**

```mermaid
flowchart TD
    A([시작]):::success --> B{필수·부호 위반?\n입력 검증}:::decision
    B -- 위반 --> C[/VALIDATION_ERROR/]:::error
    B -- 통과 --> D[주문자 조회]:::process
    D --> E{주문자 존재?}:::decision
    E -- 없음 --> F[/USER_NOT_FOUND/]:::error
    E -- 있음 --> G[상품 조회]:::process
    G --> H{상품 존재?}:::decision
    H -- 없음 --> I[/PRODUCT_NOT_FOUND/]:::error
    H -- 있음 --> J{상품의 활성 핫딜 있음?\nnow ∈ [startAt,endAt) ACTIVE}:::decision
    J -- 없음 --> K[/NO_ACTIVE_DEAL/]:::error
    J -- 있음 --> L{이미 살아있는 주문 있음?\n사전 가드}:::decision
    L -- 있음 --> M[/ALREADY_PURCHASED/]:::error
    L -- 없음 --> N{수량 ≤ maxPerOrder?}:::decision
    N -- 초과 --> O[/EXCEEDS_PURCHASE_LIMIT/]:::error
    N -- 이하 --> P[주문 PENDING 저장\norderNo·금액·만료 확정]:::process
    P --> Q{활성 유니크 위반?\nuk_orders_active}:::decision
    Q -- 위반 --> M
    Q -- 통과 --> R{핫딜 잔여 ≥ 수량?}:::decision
    R -- 부족 --> S[/SOLD_OUT/]:::error
    R -- 충분 --> T[핫딜 재고 차감\n잔여 -= 수량]:::process
    T --> U{차감 경합?\n낙관락 version}:::decision
    U -- 충돌 --> V[/PURCHASE_CONFLICT/]:::error
    U -- 성공 --> W([주문 정보 반환]):::success

    classDef error fill:#f8d7da,stroke:#dc3545,color:#dc3545,font-weight:bold
    classDef success fill:#d4edda,stroke:#28a745,color:#155724
    classDef process fill:#d1ecf1,stroke:#17a2b8,color:#0c5460
    classDef decision fill:#fff3cd,stroke:#ffc107,color:#856404
```

> **설계 노트 — 검증/실행 순서**: 입력검증(컨트롤러) → 주문자(Facade) → 상품(Facade) → 활성핫딜 해소(Facade) → [OrderService] 1인1개 사전가드 → `Order.create`(maxPerOrder 검증, 메모리) → save(유니크 최종 직렬화) → [stock] 차감(SOLD_OUT/낙관락). 메모리 검증(maxPerOrder)을 쓰기(주문 save·재고 차감) 앞에 둬 불필요한 DB 쓰기를 막고, 쓰기는 모든 거절 사유를 통과한 뒤에 한다.

**엔티티 메서드 설계**

`Order.create`는 maxPerOrder 검증·PENDING·orderNo·금액·만료를 캡슐화하고, `HotDealStock.deduct`는 잔여 검사·차감을 캡슐화한다([entity.md](../../.claude/rules/entity.md)). 아래는 구현 가이드용 의사 코드다. 메서드 존재는 TDD GREEN 단계에서 정당화한다.

```java
// Order — 정적 팩토리 (orderNo·금액·상태·만료를 한곳에 캡슐화; Service 는 OrderProperties 의 timeout 만 전달)
public static Order create(User user, HotDeal hotDeal, Product product, int quantity, Duration paymentTimeout) {
    validatePurchaseLimit(quantity, hotDeal.getMaxPerOrder());
    return Order.builder()
        .user(user)
        .hotDeal(hotDeal)
        .product(product)
        .quantity(quantity)
        .orderNo(UUID.randomUUID().toString())
        .orderAmount(hotDeal.getDealPrice().multiply(BigDecimal.valueOf(quantity)))
        .status(OrderStatus.PENDING)
        .expiresAt(LocalDateTime.now().plus(paymentTimeout))
        .build();
}

private static void validatePurchaseLimit(int quantity, int maxPerOrder) {
    if (quantity > maxPerOrder) {
        throw new DomainException(EXCEEDS_PURCHASE_LIMIT);
    }
}

// HotDealStock — 차감 도메인 메서드 (낙관락: 엔티티가 PC 에 있어야 version 추적)
public void deduct(int quantity) {
    if (remainingQuantity < quantity) {
        throw new DomainException(SOLD_OUT);
    }
    remainingQuantity -= quantity;
}
```

**쿼리 설계**

활성 핫딜 해소는 조건이 3개(productId + 시각 범위 + status)라 가독성을 위해 `@Query` JPQL을 쓴다. 사전 가드는 `status IN (PENDING, PAID)` 존재 검사라 `@Query`로 의미를 명명한다([repository.md](../../.claude/rules/repository.md)).

```java
// HotDealRepository — 활성 핫딜 해소: now ∈ [startAt, endAt), ACTIVE, 최신 1건만 (LIMIT 1)
@Query("""
    SELECT h FROM HotDeal h
    WHERE h.product = :product
      AND h.status = 'ACTIVE'
      AND h.startAt <= :now
      AND :now < h.endAt
    ORDER BY h.startAt DESC
    LIMIT 1
""")
Optional<HotDeal> findActiveByProduct(@Param("product") Product product, @Param("now") LocalDateTime now);

// HotDealStockRepository — 차감 위해 행을 PC 에 로드 (낙관락 version 추적)
Optional<HotDealStock> findByHotDealId(Long hotDealId);

// OrderRepository — 사전 1인1개 가드 (살아있는 주문 = PENDING/PAID, 엔티티 객체 파라미터)
@Query("""
    SELECT COUNT(o) > 0 FROM Order o
    WHERE o.user = :user
      AND o.hotDeal = :hotDeal
      AND o.status IN ('PENDING', 'PAID')
""")
boolean existsActiveOrder(@Param("user") User user, @Param("hotDeal") HotDeal hotDeal);
```

> **설계 노트 — 활성 핫딜 단건 조회(LIMIT 1)**: 기간 겹침 금지 가드(등록)가 정상 운영에서 상품당 활성 핫딜을 0/1건으로 보장하지만, 관리자 동시 등록 경합(수용 — [ADR-0007 결정4](../adr/0007-hotdeal-state-operations.md))의 이론적 N건이 있어도 쿼리에 `ORDER BY startAt DESC LIMIT 1`을 걸어 DB가 최신 1건만 반환한다(`Optional<HotDeal>`). 따라서 `NonUniqueResult` 없이 단건이고, Service는 `orElseThrow(NO_ACTIVE_DEAL)`만 한다 — List 전체 로딩·`stream().findFirst()`가 필요 없다. N건 동시성 테스트는 두지 않는다.
