# 결제(payment) API 설계 문서

## 개요

결제(payment)는 회원이 PENDING 주문에 대해 토스 페이먼츠 결제 승인을 확정하는 도메인이다. 클라이언트가 토스 결제창에서 받은 paymentKey를 우리 서버에 전달하면, 서버가 금액을 검증하고 토스 결제 승인 API를 호출해 주문 상태를 PENDING→PAID로 전이한다.

- **슬라이스 3 범위**: 토스 결제 승인 + PENDING→PAID 전이 + 만료↔결제 경합 처리.
- **슬라이스 4 범위**: 결제 승인 순서 정합화 — `markPaid` 선점을 토스 승인 앞으로 재배치(만료·이중 승인을 토스 호출 전 차단) + 만료 조건부 전이 구현 정합화([order System 설계](../order/api-design-system.md)).
- **다음 범위**: 결제 실패 이력 보관(FAILED·CANCELED), 결제 웹훅·대사(토스 승인 성공 후 서버 다운 잔여 복구).
- **문서 구조**: User API(결제 승인) → [api-design-user.md](api-design-user.md).

---

## 변경 이력

| 버전 | 일자 | 내용 |
|------|------|------|
| v0.1 | 2026-06-24 | 결제 승인 1개 API 설계 초안 (슬라이스 3) |
| v0.2 | 2026-06-25 | 슬라이스 4 — `confirm` 흐름 재배치(`markPaid` 선점 → 토스), 만료↔결제 양방향 조건부 전이 정합화, 토스 취소 동기 보정 철회 |

---

## 공통 정의

### 엔티티 구조

결제 승인은 `Payment`(결제) 행을 생성하고, `Order`(주문) 상태를 PENDING→PAID로 전이한다. Payment는 Order를 논리 참조하고, 승인 결과(금액·PG 키·승인 시각)를 저장한다.

#### Payment (결제)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | 결제 고유 ID (BaseEntity) |
| amount | BigDecimal | 결제 금액 (필수, `DECIMAL(12,0)`) |
| status | PaymentStatus | 결제 상태 (슬라이스3에서 enum 확정) |
| pgPaymentKey | String | PG 거래 키 (토스 paymentKey, UNIQUE, max 200자) |
| idempotencyKey | String | 멱등키 (서버 생성 UUID, 승인 재시도 시 같은 키 재사용) |
| approvedAt | LocalDateTime | 승인 시각 (토스 응답 `approvedAt`) |
| orderId | Long | 대상 주문 ID (FK 값 칼럼, `@ManyToOne` 매핑 없음) |
| createdAt | LocalDateTime | 생성일시 (BaseEntity) |
| updatedAt | LocalDateTime | 수정일시 (BaseEntity) |

> **Order:Payment = 1:N**: 한 주문에 결제 시도가 여러 번일 수 있다(카드 한도 초과 후 다른 카드 재시도). 토스는 시도마다 다른 paymentKey를 발급하므로 "영수증 1장 = 1행"으로 쌓는다([ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정1). 슬라이스3에서는 승인 성공 시만 DONE 상태로 행을 생성한다.
>
> **Payment→Order 연관 매핑 없음**: Facade 검증 단계(독립 readOnly TX)에서 Order를 조회한 뒤 TX가 닫히면 Order가 detached 상태가 된다. 저장 단계(txTemplate TX)에서 detached Order를 `@ManyToOne`에 넣으면 `detached entity passed to persist`가 발생한다. entity.md 규칙("객체 탐색이 불필요한 참조는 FK 값 칼럼(Long) + 전용 repository 조회 허용")에 따라 `Long orderId`로 저장한다 — DB 칼럼(`order_id`)은 동일하므로 DDL 변경 없이 엔티티 코드만 조정.

#### Order (주문) — 슬라이스3 전이 추가

| 전이 | 조건 | 처리 |
|------|------|------|
| PENDING → PAID | 결제 선점 (토스 승인 앞) | `@Modifying UPDATE WHERE status='PENDING'`, affected==1 관문 |
| PENDING → CANCELED | 만료 스케줄러 | `@Modifying UPDATE WHERE status='PENDING'`, affected==1일 때만 재고 복원 |

> **양방향 조건부 전이**: Order에 `@Version`이 없으므로 결제(PAID)·만료(CANCELED) **둘 다** `UPDATE ... WHERE status='PENDING'` 조건부 전이로 경합을 처리한다([ADR-0004 결정3](../adr/0004-stock-reservation-lifecycle.md)). 확인(WHERE)과 쓰기(SET)가 한 문장이라 어느 쪽이 먼저 커밋하든 진 쪽이 affected==0으로 그 사실을 안다. 결제 선점이 affected==0이면 만료로 CANCELED됐거나 중복 승인 → `ORDER_STATUS_CONFLICT`(409). 슬라이스4에서 **만료 쪽도 조건부 전이로 정합화**한다 — 슬라이스2 구현이 변경 감지(dirty checking)로 단순화돼 만료 쪽이 무조건 덮어쓰던 것을 바로잡아, 결제 PAID를 만료가 덮는 경합을 막는다([order System 설계](../order/api-design-system.md)).

---

### Enum 정의

#### PaymentStatus (결제 상태)

| 값 | 설명 |
|----|------|
| PENDING | 승인 전 (슬라이스4 — 실패 이력 보관 시 활용) |
| DONE | 승인 성공 |
| FAILED | 승인 실패 (슬라이스4) |
| CANCELED | 취소 (슬라이스4) |

> 슬라이스3에서는 승인 성공 시 DONE 상태로만 Payment 행을 생성한다. FAILED·CANCELED 행 보관은 슬라이스4.

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

### ExceptionCode

결제 승인 흐름의 에러는 order(주문 상태), payment(PG 결과), hotdeal(딜 상태)에 걸쳐 발생한다.

| ExceptionCode | 소속 enum | HttpStatus | 발생 |
|---------------|-----------|:----------:|------|
| ORDER_NOT_FOUND | OrderExceptionCode | 404 | orderNo에 해당 주문 없음 |
| AMOUNT_MISMATCH | OrderExceptionCode | 400 | 요청 amount ≠ order.orderAmount (금액 위변조 방어) |
| ORDER_STATUS_CONFLICT | OrderExceptionCode | 409 | 조건부 UPDATE affected==0 — 만료로 CANCELED됐거나 이미 PAID |
| HOTDEAL_CANCELED | HotDealExceptionCode | 409 | 핫딜이 관리자 취소(CANCELED) 상태 — 승인 차단 |
| PAYMENT_GATEWAY_ERROR | PaymentExceptionCode | 502 | 토스 통신 오류·타임아웃 |
| PAYMENT_REJECTED | PaymentExceptionCode | 402 | 토스가 승인 거부 (잔액 부족·한도 초과 등) |

> 에러 분류 세부: `TossPaymentClient`(어댑터)가 토스 에러코드를 `PAYMENT_GATEWAY_ERROR`(재시도 불가 오류)·`PAYMENT_REJECTED`(결제 수단 거부) 두 종류로 접는다. 재시도 가능·무의미 세분화는 슬라이스4 범위([ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3·4).

---

### 알려진 제약 / 전제

| 항목 | 내용 | 근거 |
|------|------|------|
| 승인 흐름 | 클라이언트 confirm 방식 — 프론트가 토스 successUrl에서 paymentKey·orderId·amount를 받아 우리 서버에 전달, 서버가 토스 confirm API 호출 | 토스 공식 권장 |
| PG 어댑터 | PaymentFacade(트랜잭션 밖) → PaymentGatewayClient → TossPaymentClient → TossHttpClient | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3 |
| 트랜잭션 경계 | PaymentFacade `confirm`에 `@Transactional` 단일 선언 — 조회·검증·**선점**·토스 호출·Payment 생성이 한 TX. 선점이 토스 앞이라 토스 실패 시 롤백으로 선점한 PAID가 PENDING 복귀(우리 DB만 되돌림) | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3 |
| 경합 처리 | 결제(PAID)·만료(CANCELED) **양방향** 조건부 전이(`WHERE status='PENDING'`, affected==1)로 직렬화 — 선점이 토스 앞이라 만료·이중 승인을 **토스 호출 전** 차단 | [ADR-0004 결정3](../adr/0004-stock-reservation-lifecycle.md) |
| 금액 검증 | 서버가 order.orderAmount와 request.amount를 비교 — 토스 호출 전 400 AMOUNT_MISMATCH | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) "서버가 주문 시점 저장 금액으로" |
| 핫딜 취소 차단 | 승인 시점 핫딜 status==CANCELED이면 돈 움직이기 전 HOTDEAL_CANCELED(409) | [ADR-0007 결정2](../adr/0007-hotdeal-state-operations.md) |
| 슬라이스 범위 | 슬라이스3=승인+PAID 전이. 슬라이스4=선점 순서 재배치 + 만료 조건부 정합화. 결제 실패 이력(FAILED·CANCELED)·웹훅·대사는 다음 범위 | — |
| 마이그레이션 | payments 테이블 status 칼럼: String → VARCHAR(20) + PaymentStatus enum 매핑 | TODO(slice-3) 해소 |
| 이중 승인 차단 | 슬라이스4에서 **선점 순서 재배치**로 만료·이중 승인을 토스 호출 전 차단(돈이 나가지 않음). 토스 취소 동기 보정은 철회 — 서버 다운 잔여(토스 성공 후 커밋 실패)는 동기로 불가해 비동기 후속(웹훅·대사) | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) "함께 묶이는 방어" 갱신 |
