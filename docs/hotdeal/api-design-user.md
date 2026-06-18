# 핫딜 User API 설계 문서

> 공통 정의(엔티티·Enum·응답 형식·ExceptionCode·제약)는 [api-design.md](api-design.md) 참조.

## API 목록 (1개)

| # | Method | Endpoint | 설명 |
|---|--------|----------|------|
| 1 | GET | `/api/hotdeals/{id}` | 핫딜 단건 조회 |

---

## 1. 핫딜 단건 조회

```
GET /api/hotdeals/{id}
```

> 공개 카탈로그에서 핫딜 한 건의 특가·총 한정 수량·잔여 수량·판매 기간·상태를 보여 준다. 비회원도 볼 수 있다. 조회 대상은 진행 중·예정 ACTIVE 핫딜이며(취소·종료 핫딜은 404), 노출된 핫딜의 진행 중/예정/매진 구분은 서버가 별도 상태값으로 주지 않고 클라이언트가 `startAt`/`remainingQuantity`로 판단한다([ADR-0007 결정1](../adr/0007-hotdeal-state-operations.md)).
>
> 인증: 공개. `SecurityConfig`의 `permitAll`에 `GET /api/hotdeals/**`를 추가한다.

**Request**

| 구분 | 파라미터 | 타입 | 필수 | 설명 |
|------|----------|------|:--:|------|
| Path | id | Long | O | 조회할 핫딜 ID |

요청 본문 없음.

**검증**

| 검증 항목 | 방식 | 에러코드 |
|-----------|------|----------|
| 진행 중·예정(ACTIVE, 미종료) 핫딜만 조회 — 취소·종료 핫딜 제외 | 비즈니스 검증 (조회 쿼리 조건) | HOTDEAL_NOT_FOUND |

> **설계 노트 — 취소·종료 처리**: 조회는 **진행 중이거나 시작 전(예정)인 ACTIVE 핫딜만** 반환한다 — 쿼리 조건 `status = 'ACTIVE' AND endAt > now`. 취소(CANCELED)·종료(`endAt` 지남) 핫딜은 `HOTDEAL_NOT_FOUND`(404)로 노출하지 않는다(지난 핫딜의 가격·시점이 id 열거로 새는 것 차단). 예정 핫딜은 노출한다([PRD 요구 2](../design/hotdeal-prd.md) 정시 오픈 전 노출). 이 시각 필터는 저장 상태값이 아니라 조회 시점의 live 판단이라 "진행 상태값·스케줄러 없음"([ADR-0007 결정1](../adr/0007-hotdeal-state-operations.md))과 일관된다.
>
> 잔여 수량 조회는 핫딜이 존재하면 핫딜 예약 재고(`HotDealStock`)도 1:1로 반드시 존재한다(등록 트랜잭션에서 함께 생성). 다만 DB FK 제약이 없어 정합이 깨질 가능성에 대비해, 재고가 없으면 500을 노출하지 않고 `HOTDEAL_NOT_FOUND`(404)로 처리한다.

**Response 필드**

| 필드 | 타입 | 설명 | 매핑 |
|------|------|------|------|
| id | Long | 핫딜 ID | hotDeal.id |
| productId | Long | 대상 상품 ID | hotDeal.product.id |
| dealPrice | BigDecimal | 특가 | hotDeal.dealPrice |
| totalQuantity | Integer | 총 한정 수량 | hotDeal.totalQuantity |
| remainingQuantity | Integer | 잔여 수량 | hotDealStock.remainingQuantity (별도 조회) |
| startAt | LocalDateTime | 판매 시작 시각 | hotDeal.startAt |
| endAt | LocalDateTime | 판매 종료 시각 | hotDeal.endAt |
| status | HotDealStatus | 상태 (ACTIVE/CANCELED 표시값, 파생 상태 아님) | hotDeal.status |

**Response 예시 (성공)**

```json
{
  "result": true,
  "data": {
    "id": 42,
    "productId": 10,
    "dealPrice": 9900,
    "totalQuantity": 100,
    "remainingQuantity": 73,
    "startAt": "2026-06-20T07:00:00",
    "endAt": "2026-06-20T09:00:00",
    "status": "ACTIVE"
  }
}
```

**Response 예시 (실패)**

```json
{
  "result": false,
  "error": {
    "code": "HOTDEAL_NOT_FOUND",
    "message": "핫딜을 찾을 수 없습니다."
  }
}
```

> **설계 노트 — 응답 필드 결정**: `createdAt`은 공개 카탈로그에 불필요해 제외한다. `id`는 공개 순번 노출이지만 [가설 4장 식별자 정책](../design/hotdeal-purchase-hypothesis.md)상 허용된다. 파생 상태(진행/매진)는 넣지 않는다 — `status`는 관리자 취소 여부를 나타내는 표시값일 뿐 진행 단계가 아니다.
>
> **설계 노트 — 변환 방식**: 응답은 핫딜+핫딜 예약 재고 두 엔티티에서 조립한다. MapStruct는 **여러 소스 매개변수**를 받을 수 있으므로 `HotDealMapper`(MapStruct)의 `HotDealDetailResponse toDetailResponse(HotDeal hotDeal, HotDealStock hotDealStock)`(+`@Mapping`)로 만든다([dto.md](../../.claude/rules/dto.md) 컨벤션). 등록 응답도 같은 매퍼의 `toCreateResponse(HotDeal)`.

**구현 로직**

```mermaid
flowchart TD
    A([시작]):::success --> B[핫딜 조회\nid로 검색 · 진행/예정 ACTIVE만(취소·종료 제외)]:::process
    B --> C{조회 가능한 핫딜 존재?}:::decision
    C -- 없음·취소·종료 --> D[/HOTDEAL_NOT_FOUND/]:::error
    C -- 있음 --> E[잔여 수량 조회\n재고 별도 조회]:::process
    E --> F([핫딜 상세 반환\n핫딜 + 잔여 수량 조립]):::success

    classDef error fill:#f8d7da,stroke:#dc3545,color:#dc3545,font-weight:bold
    classDef success fill:#d4edda,stroke:#28a745,color:#155724
    classDef process fill:#d1ecf1,stroke:#17a2b8,color:#0c5460
    classDef decision fill:#fff3cd,stroke:#ffc107,color:#856404
```

> **설계 노트 — 잔여 수량 별도 조회**: 핫딜 예약 재고(HotDealStock)는 핫딜의 JPA 연관 필드가 아니라 핫딜 ID를 raw 값으로 보유하므로, 연관 탐색이 불가능하다. 잔여 수량은 `HotDealStockRepository`로 별도 조회한다. 단건 조회라 두 번의 SELECT여도 성능 이슈가 없다(조인 프로젝션은 현재 범위에서 과한 설계).
>
> **설계 노트 — productId 접근**: `hotDeal.getProduct().getId()`는 LAZY 프록시의 식별자라 DB 추가 조회 없이 FK 값을 읽는다. 따라서 응답에 `productId`를 넣어도 상품을 실제 로딩하지 않는다(상품명 등 다른 필드는 현재 범위 응답에 없음).

**쿼리 설계**

핫딜 조회는 취소·종료 핫딜을 빼고 진행/예정 핫딜만 반환하므로 `id` + `status` + `endAt` 세 조건이다(예정 핫딜 노출을 위해 `startAt` 조건은 두지 않음). 잔여 수량은 단순 1 조건 derived query로 조회한다([repository.md](../../.claude/rules/repository.md)). 아래는 구현 가이드용 의사 코드다.

```java
// HotDealRepository — 진행 중·예정 ACTIVE만 (취소·종료 제외)
@Query("""
    SELECT h FROM HotDeal h
    WHERE h.id = :id
      AND h.status = 'ACTIVE'
      AND h.endAt > :now
""")
Optional<HotDeal> findViewableById(@Param("id") Long id, @Param("now") LocalDateTime now);

// HotDealStockRepository — 잔여 수량 조회
Optional<HotDealStock> findByHotDealId(Long hotDealId);
```

> **설계 노트 — 조회 조건**: `status = 'ACTIVE'`로 취소 핫딜을, `endAt > :now`로 종료 핫딜을 제외한다 — 둘 다 `HOTDEAL_NOT_FOUND`. `startAt` 조건은 없어 예정(시작 전) 핫딜은 노출된다. `:now`는 서비스가 넘긴다(테스트 시 고정 시각 주입이 쉬움).

**테스트 리스트**

> vertical TDD 사이클로 한 줄씩 누적한다([commit-checkpoint.md](../../.claude/rules/commit-checkpoint.md)). 설계 단계는 헤더만 둔다(placeholder 행 금지).

| # | 테스트 케이스 | 시나리오 | 상태 | 작성일 |
|---|---------------|----------|------|--------|
