# 결제(payment) API 설계 문서

## 개요

결제(payment)는 회원이 PENDING 주문에 대해 토스 페이먼츠 결제 승인을 확정하는 도메인이다. 클라이언트가 토스 결제창에서 받은 paymentKey를 우리 서버에 전달하면, 서버가 금액을 검증하고 토스 결제 승인 API를 호출해 주문 상태를 PENDING→PAID로 전이한다.

- **슬라이스 3 범위**: 토스 결제 승인 + PENDING→PAID 전이 + 만료↔결제 경합 처리.
- **슬라이스 4 범위**: 결제 승인 순서 정합화 — `markPaid` 선점을 토스 승인 앞으로 재배치(만료·이중 승인을 토스 호출 전 차단) + 만료 조건부 전이 구현 정합화([order System 설계](../order/api-design-system.md)).
- **슬라이스 5 범위**: 결제 확정 시 ProductStock 실물·예약 차감(`confirmSale`) — `markPaid` 선점 직후·토스 앞에 재고 차감 선점 ([ADR-0011 결정3](../adr/0011-product-inventory-reservation.md) 누락 정합화).
- **Phase B1 범위**: 토스 결제 승인 **실연동** — `TossPaymentClient` 실HTTP 호출(stub 제거) + `TossHttpClient`(전송층) 신설. 외부 토스 호출을 DB 트랜잭션 **밖**으로 분리(TX 경계 교정), `PgConfirmResult`를 성공/거절/미확정(in-doubt) 세 결과로 확장, 미확정 결제를 `PaymentStatus.IN_DOUBT`로 보존, 토스 멱등키 헤더 전송 + `pgPaymentKey` UNIQUE 충돌 멱등 처리. (아래 "Phase B1 — 토스 결제 실연동" 절)
- **후속 범위(B2/B3+)**: **B2** = **IN_DOUBT 해소 스케줄러** — IN_DOUBT 결제를 주기적으로 결제 조회해 확정(DONE 확정 / IN_PROGRESS는 confirm 멱등 재시도로 매출 복구 / 실패는 주문 CANCELED·재고 복원). 확정 주체는 토스(10분이면 자동 EXPIRED), 우리는 폴링으로 읽음 ([api-design-system.md](api-design-system.md)). **B3+** = 웹훅(해소보다 빠른 실시간 해소 최적화 — **해소 대체 아님**, 유실 가능)·넓은 해소(배민식 PG 전체 대조)·앱 밖 사후 취소/환불/분쟁. **이번 Phase B1 범위 밖** — IN_DOUBT 행은 B1에서 *생성·보존*까지만 하고, 그 *해소*는 B2 해소에서 다룬다.
- **문서 구조**: User API(결제 승인) → [api-design-user.md](api-design-user.md).

---

## 변경 이력

| 버전 | 일자 | 내용 |
|------|------|------|
| v0.1 | 2026-06-24 | 결제 승인 1개 API 설계 초안 (슬라이스 3) |
| v0.2 | 2026-06-25 | 슬라이스 4 — `confirm` 흐름 재배치(`markPaid` 선점 → 토스), 만료↔결제 양방향 조건부 전이 정합화, 토스 취소 동기 보정 철회 |
| v0.3 | 2026-06-25 | 슬라이스 5 — 결제 확정 시 ProductStock 실물·예약 차감(`confirmSale`) 추가, 토스 앞 재고 차감 선점 ([ADR-0011](../adr/0011-product-inventory-reservation.md) 결정3 정합화) |
| v0.4 | 2026-06-29 | Phase B1 — 토스 실연동: TX 경계 교정(토스 호출을 DB TX 밖으로), `PgConfirmResult` 3결과(승인/거절/미확정) 확장, `PaymentStatus.IN_DOUBT` 신설, 멱등키 헤더 + `pgPaymentKey` UNIQUE 충돌 멱등, `TossHttpClient` 신설 규약 |

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
| pgPaymentKey | String | PG 거래 키 (토스 paymentKey, UNIQUE, max 200자 — 멱등키로도 재사용) |
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
| DONE | 승인 성공 |
| **IN_DOUBT** | **결과 미확정 — 토스 호출이 타임아웃·응답 유실로 끝나 "승인됐는지 알 수 없음". 돈이 나갔을 수 있으므로 롤백 금지. 해소 스케줄러(B2)가 DONE 또는 FAILED로 확정** (Phase B1 신설) |
| FAILED | 승인 실패 — 해소가 실패 확정(EXPIRED·404 등) 시 기록 (confirm 즉시 거절은 Payment 미생성) |

> 슬라이스3~5에서는 승인 성공 시 DONE 상태로만 Payment 행을 생성했다. Phase B1에서 **IN_DOUBT**(결과 미확정)를 추가한다 — 실HTTP 호출이 타임아웃되거나 응답이 유실되면 "성공도 실패도 아닌" 행을 남겨 나중에 해소로 확정한다.
>
> **갱신(2026-07-06) — PENDING·CANCELED 제거**: 설계 당시 예비해 둔 PENDING(승인 전)·CANCELED(취소)는 B2 완료까지 사용처가 생기지 않아 enum에서 제거했다(각 코드는 테스트로 정당화 원칙). 당시 "PENDING과 IN_DOUBT의 구분" 논의(승인 전 vs 결과 미확정은 별개 값이어야 한다)는 IN_DOUBT 신설의 근거 기록으로 유효하다 — 취소/환불 기능이 생기면 CANCELED를 그때 다시 추가한다.

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

결제 승인 흐름의 에러는 order(주문 상태), payment(PG 결과), hotdeal(딜 상태), stock(재고 장부)에 걸쳐 발생한다.

| ExceptionCode | 소속 enum | HttpStatus | 발생 |
|---------------|-----------|:----------:|------|
| ORDER_NOT_FOUND | OrderExceptionCode | 404 | orderNo에 해당 주문 없음 |
| AMOUNT_MISMATCH | OrderExceptionCode | 400 | 요청 amount ≠ order.orderAmount (금액 위변조 방어) |
| ORDER_STATUS_CONFLICT | OrderExceptionCode | 409 | 조건부 UPDATE affected==0 — 만료로 CANCELED됐거나 이미 PAID |
| HOTDEAL_CANCELED | HotDealExceptionCode | 409 | 핫딜이 관리자 취소(CANCELED) 상태 — 승인 차단 |
| PRODUCT_STOCK_INCONSISTENT | StockExceptionCode | 500 | 결제 확정 재고 차감 affected==0 — 예약/실물 장부 불일치(정상이면 안 남, 운영 알림) |
| PAYMENT_GATEWAY_ERROR | PaymentExceptionCode | 502 | 토스 통신 오류 — **결과 확정**(요청이 토스에 닿지 못함, 돈 안 나감)인 재시도 가능 실패 |
| PAYMENT_REJECTED | PaymentExceptionCode | 402 | 토스가 승인 거부 (잔액 부족·한도 초과 등) — 결과 확정된 비즈니스 거절 |

> **Phase B1 — 미확정(in-doubt)은 예외가 아니라 결과값**: 타임아웃·응답 유실 등 "성공도 실패도 아닌" 경우는 `DomainException`을 던지지 않는다. 던지면 Facade TX가 무조건 롤백→PENDING 복귀하는데, 토스에선 돈이 실제로 나갔을 수 있어 롤백이 위험하다. 대신 `PgConfirmResult`가 **미확정 결과값**으로 돌려받아 Payment를 `IN_DOUBT`로 저장한다(롤백 없음). 따라서 미확정 전용 ExceptionCode는 두지 않는다 — 아래 "결과 분류" 표 참조.
>
> 에러 분류 세부: `TossPaymentClient`(어댑터)가 토스 응답/예외를 **4가지 결과값**으로 접는다 — 승인 `Approved`·거절 `Rejected`·통신오류 `GatewayError`·미확정 `InDoubt`. Facade는 결과를 `switch`(try-catch 없이)로 받아 거절→402 `PAYMENT_REJECTED`·통신오류→502 `PAYMENT_GATEWAY_ERROR`로 throw하고, 미확정은 IN_DOUBT로 보존한다. **예상 못한 예외**(코드 버그·응답 매핑 실패 등)는 결과값으로 접지 않고 그대로 **전파(500)** — catch-all로 삼키면 버그가 미확정으로 위장되기 때문(예외 삼키기 안티패턴). 그로 인한 잔여(주문 PAID + Payment 없음)는 **B2 해소가 "주문 PAID + 결제미완" 기준으로 스캔**해 정리한다([ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3 — "재시도 가능/재시도 무의미/상태 불명").

---

### 알려진 제약 / 전제

| 항목 | 내용 | 근거 |
|------|------|------|
| 승인 흐름 | 클라이언트 confirm 방식 — 프론트가 토스 successUrl에서 paymentKey·orderId·amount를 받아 우리 서버에 전달, 서버가 토스 confirm API 호출 | 토스 공식 권장 |
| PG 어댑터 | PaymentFacade → PaymentGatewayClient(계약) → TossPaymentClient(어댑터·분류) → TossHttpClient(전송) | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3 |
| 트랜잭션 경계 | **Phase B1 교정** — 단일 TX 폐기. 토스 호출(외부 I/O)은 DB TX **밖**에서 수행, 그 앞뒤만 짧은 TX로 닫는다: TX1(선점+재고차감 커밋) → 토스 호출(TX 밖) → TX2(결과 반영). 아래 "Phase B1 — 토스 결제 실연동" 절 | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3 + Phase B1 교정 |
| 경합 처리 | 결제(PAID)·만료(CANCELED) **양방향** 조건부 전이(`WHERE status='PENDING'`, affected==1)로 직렬화 — 선점이 토스 앞이라 만료·이중 승인을 **토스 호출 전** 차단 | [ADR-0004 결정3](../adr/0004-stock-reservation-lifecycle.md) |
| 금액 검증 | 서버가 order.orderAmount와 request.amount를 비교 — 토스 호출 전 400 AMOUNT_MISMATCH | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) "서버가 주문 시점 저장 금액으로" |
| 핫딜 취소 차단 | 승인 시점 핫딜 status==CANCELED이면 돈 움직이기 전 HOTDEAL_CANCELED(409) | [ADR-0007 결정2](../adr/0007-hotdeal-state-operations.md) |
| 슬라이스 범위 | 슬라이스3=승인+PAID 전이. 슬라이스4=선점 순서 재배치 + 만료 조건부 정합화. 슬라이스5=결제 확정 ProductStock 차감. 결제 실패 이력(FAILED·CANCELED)·웹훅·해소는 다음 범위 | — |
| 마이그레이션 | payments 테이블 status 칼럼: String → VARCHAR(20) + PaymentStatus enum 매핑 | TODO(slice-3) 해소 |
| 이중 승인 차단 | 슬라이스4에서 **선점 순서 재배치**로 만료·이중 승인을 토스 호출 전 차단(돈이 나가지 않음). 토스 취소 동기 보정은 철회 — 서버 다운 잔여(토스 성공 후 커밋 실패)는 동기로 불가해 비동기 후속(웹훅·해소) | [ADR-0008](../adr/0008-payment-model-pg-boundary.md) "함께 묶이는 방어" 갱신 |
| 재고 차감(슬라이스5) | 결제 확정 시 ProductStock 실물·예약 1씩↓(`confirmSale`, reserve 대칭 조건부 UPDATE). `markPaid` 선점 직후·토스 앞이라 차감 실패(장부 불일치) 시 토스 미호출·롤백. 품절은 주문 단계(HotDealStock)가 막아 결제 차감은 정상이면 항상 성공 | [ADR-0011 결정3](../adr/0011-product-inventory-reservation.md) |
| **외부 HTTP 연동(B1)** | `TossHttpClient`가 프로젝트 **첫 외부 HTTP 클라이언트** — `RestClient` 빈을 `global/config/TossHttpClientConfig`가 구성, connect/read 타임아웃·시크릿 인증·멱등키 헤더를 여기서 정립(이후 외부 연동의 참조 패턴) | 선례 0건 — B1이 1호 |
| **TX 경계 교정(B1)** | 토스 호출은 DB TX 밖. TX1(선점·차감) → 토스 → TX2(결과 반영). 거절은 **실패 확정**(주문 CANCELED·핫딜+상품 재고 방출), 통신오류는 **보상 롤백**(주문 PENDING 복귀·재고 복원), 미확정은 보상 없이 IN_DOUBT 보존 | 아래 B1 절 |
| **부하 테스트 제외(B1 유지)** | 통합 테스트·부하 테스트는 토스 실API를 호출하지 않는다 — `PaymentGatewayClient` 대역 유지. 실HTTP는 `TossHttpClient` 단위 테스트(MockWebServer)로 격리 | [ADR-0001](../adr/0001-payment-gateway-toss.md) 테스트 모드 한계 |

---

## Phase B1 — 토스 결제 실연동

> 슬라이스3~5에서 `TossPaymentClient.confirm`은 `UnsupportedOperationException` stub이었고 통합 테스트는 `PaymentGatewayClient` 대역으로 그린 상태다. Phase B1은 그 **단 한 곳의 seam**을 실HTTP 호출로 채우고, 동기 confirm으로 못 막는 잔여(응답 유실·중복 수신)를 **TX 경계 분리 + 미확정 결과 모델 + 멱등**으로 보강한다. 설계 결정은 [ADR-0008](../adr/0008-payment-model-pg-boundary.md)·[ADR-0001](../adr/0001-payment-gateway-toss.md)에 근거하며 새 결정(TX 경계 교정·IN_DOUBT·in-doubt 결과 모델)은 ADR 갱신 권고 대상이다(아래 "ADR 권고").

### B1-1. 트랜잭션 경계 교정 — 토스 호출을 DB TX 밖으로

기존 설계(슬라이스4·5)는 "조회·검증·선점·재고차감·토스 호출·Payment 생성을 **하나의 `@Transactional`**"로 묶고, "결제는 사용자가 순차 유입하므로 TX 안에 토스 응답 대기를 둬도 규모 문제 없음"으로 정당화했다. **Phase B1에서 이 의도를 교정한다.**

- **교정 이유**: 외부 I/O(토스 왕복)가 DB 트랜잭션 안에 들어가면, 그 왕복 내내 **선점한 주문 행 잠금 + DB 커넥션**을 잡은 채 대기한다. 토스가 느려지거나(테스트 샌드박스·네트워크 지연) 타임아웃이 길면 커넥션 풀이 외부 대기로 고갈돼 **결제와 무관한 다른 요청까지 막힌다**(외부 장애가 우리 DB 가용성으로 전파). "순차 유입이라 규모 문제 없음"은 정상 응답 속도를 전제했을 뿐, 외부 지연·타임아웃·미확정 처리가 들어오는 실연동에선 성립하지 않는다.
- **교정 후 경계** — 세 구간:

```
[TX1: 선점·차감 — 짧은 @Transactional]
  조회+금액검증(getOrderForPayment) → 핫딜취소가드 → markPaid(조건부 선점) → confirmSale(재고차감)
  ↓ 커밋 (주문 PAID·재고 차감 확정)
[외부 호출 — TX 밖]
  paymentGatewayClient.confirm(...) → PgConfirmResult (승인 | 거절 | 통신오류 | 미확정)
  ↓
[TX2: 결과 반영 — 짧은 @Transactional, 결과별 분기]
  승인   → Payment(DONE) 저장
  거절   → 실패 확정: 주문 CANCELED(PAYMENT_FAILED) + 핫딜·상품 재고 방출, Payment 미생성 → PAYMENT_REJECTED(402)
  통신오류 → 보상: 주문 PENDING 복귀 + 재고 복원, Payment 미생성 → PAYMENT_GATEWAY_ERROR(502)
  미확정 → Payment(IN_DOUBT) 저장, 보상 안 함(주문 PAID 유지·재고 차감 유지) → 200 + status=IN_DOUBT(보류)
```

> **설계 노트 — 보상이 새로 필요한 이유(자동 롤백 상실)**: 기존 단일 TX에서는 토스 거부 시 같은 TX 롤백으로 선점한 PAID·재고차감이 **자동 복귀**했다. TX를 쪼개 TX1을 먼저 커밋하면 그 자동 복귀가 사라지므로, TX2에서 명시적으로 처리한다 — 거절(돈 안 나감 확정)은 **실패 확정**(주문 CANCELED(PAYMENT_FAILED) 조건부 전이 + 핫딜·상품 재고 방출, 재시도는 새 주문), 통신오류(요청 미도달)는 **명시적 보상**(주문 PENDING 복귀 = `markPending` 조건부 UPDATE, 재고 복원 = `confirmSale` 역연산). 이는 단일 TX가 공짜로 주던 원자성을 손으로 되살리는 비용이며, TX 경계 분리의 트레이드오프다.

> **설계 노트 — 만료↔결제 동시성 정합이 새 경계 위에서 유지되는 방식**: 핵심 방어인 `markPaid` 조건부 전이(`UPDATE ... WHERE status='PENDING'`, affected==1)는 **여전히 TX1 안에서·토스 앞에** 있다. 따라서 ① 만료로 CANCELED된 주문·② 이미 PAID된 주문(이중 승인)은 TX1의 선점 단계에서 affected==0으로 걸러져 **토스를 호출하지 않는다** — 슬라이스4가 세운 "토스 호출 전 차단"이 그대로 성립한다. 바뀐 것은 토스 *이후*뿐이다: 슬라이스4에선 토스 거부가 단일 TX 롤백으로 PAID를 되돌렸으나, B1에선 TX1이 이미 커밋됐으므로 TX2가 명시적으로 확정한다(거절 = 실패 확정 CANCELED, 통신오류 = PENDING 복귀 보상). 만료 스케줄러와의 경합도 동일하게 행 잠금·조건부 전이로 직렬화되며(실패 확정의 `markPaymentFailed`·보상의 `markPending` 모두 `WHERE status='PAID'` 조건부 전이라 만료가 끼어들어도 affected로 사실을 안다), 미확정(IN_DOUBT) 시에는 주문을 PAID로 **유지**해 "돈 나갔는데 주문 PENDING"을 원천 차단한다([ADR-0004 결정4](../adr/0004-stock-reservation-lifecycle.md) "성공한 결제는 되살린다"와 정합).

### B1-2. `PgConfirmResult` 결과 모델 확장 — 성공/거절/통신오류/미확정 4결과

현재 `PgConfirmResult`는 **성공값만 담는 record**(`pgPaymentKey·amount·approvedAt`)이고, 실패는 `TossPaymentClient`가 **예외를 던져** 갈렸다. 미확정(타임아웃·응답유실)은 표현할 자리가 없어 예외로 던지면 무조건 롤백→돈 나감 위험이 생긴다. B1에서 **네 결과(승인·거절·통신오류·미확정)를 표현하는 형태**로 재설계한다.

- **권장 형태 — sealed 결과 타입**: `PgConfirmResult`를 sealed interface로 두고 `Approved`(승인값 보유) / `Rejected`(거절 사유·토스 status) / `GatewayError`(통신 실패) / `InDoubt`(원인·부분 식별자)로 가른다. 호출부(Facade)는 `switch` 패턴 매칭으로 분기한다. 거절·통신오류를 throw가 아니라 result로 옮기면 "롤백이 보상"이라는 B1 흐름과 일관되고, Facade의 try-catch 없이 `switch` 한 곳에서 4결과를 처리한다(throw는 미확정에서 위험). **예상 못한 예외**(코드 버그 등)는 결과값으로 접지 않고 **전파(500)** — 어댑터가 접는 건 "토스 호출의 알려진 결과"뿐이고, catch-all 삼키기는 버그를 미확정으로 위장하는 안티패턴이다. 잔여 일관성은 삼키기가 아니라 **B2 해소가 "주문 PAID 기준"으로 스캔**(IN_DOUBT든 Payment 없음이든 다 잡음)해서 확보한다.
  - 대안(분류 enum 필드): record 한 개에 `PgResultType` enum 필드를 더하는 방식도 가능하나, 결과별로 채워지는 값이 달라 nullable 필드가 늘어 sealed가 더 정직.
- **거절(비즈니스 4xx)과 재시도 분류 기준**:

| 토스 응답 | 우리 결과 | Payment 처리 | 주문/재고 | 재시도 의미 | 사용자 응답 |
|---|---|---|---|---|---|
| 승인 성공(2xx) | `Approved` | DONE 생성 | PAID 유지·차감 유지 | — | 200 성공 |
| 승인 거부 — **거절 코드 목록(`REJECT_CODES`)에 있는 code** (카드사 거절·잔액부족·한도초과·FDS 차단 등) | `Rejected` | 미생성(실패 확정) | **CANCELED(PAYMENT_FAILED)·핫딜+상품 재고 방출** | **재시도 무의미**(같은 수단 재시도해도 거부 — 재시도는 새 주문으로) | 402 `PAYMENT_REJECTED` |
| 통신 오류 — **응답을 못 받음**: connect 실패·DNS(요청 미도달) | `GatewayError` → (Facade가) `PAYMENT_GATEWAY_ERROR` | 미생성(보상 롤백) | PENDING 복귀·재고 복원 | **재시도 가능**(돈 안 나감) | 502 |
| **미확정** — 거절 코드가 아닌 응답 전부(처리오류·모르는 code·5xx 포함) + read 타임아웃·소켓 끊김 | `InDoubt` | **IN_DOUBT 생성** | **PAID·차감 유지(보상 ❌)** | **결과 미확정**(섣불리 재시도·롤백 금지 → B2 해소) | 200(보류 — `status=IN_DOUBT`) |

> **설계 노트 — 분류 규칙(HTTP 상태 안 봄)**: 기준은 하나, **"돈이 확실히 안 빠졌나?"** ① **응답을 받은 경우**는 토스 error `code`만 본다 — **`REJECT_CODES`에 있으면 `Rejected`(돈 안 나감 확정), 그 외 전부(처리오류·모르는 code·5xx 포함)는 `InDoubt`**(안전 기본값, 애매하면 안 되돌림). ② **응답을 못 받은 경우**는 요청이 닿았는지로 — connect 실패·DNS는 `GatewayError`(안 닿음=돈 안 나감), read 타임아웃·소켓 중단은 `InDoubt`(닿았을 수 있음). 분류는 어댑터(`TossPaymentClient`)에 격리된다([ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3).

> **왜 in-doubt가 아니라 "거절"을 목록화하나**: 성공한 결제를 되돌리면 "돈 나감+주문 취소"(파국)이고, 실패를 IN_DOUBT로 남기면 해소가 정리(낭비일 뿐 안전) — 이 비대칭 때문에 **모르는 code의 안전 기본값은 `InDoubt`**다. 그래서 명시 목록은 "확실히 돈 안 나가는 거절 code"가 되고, 토스가 새 code를 추가해도 자동으로 안전측(InDoubt)에 떨어진다(allowlist drift 안전).

> **`REJECT_CODES`는 "모든 4xx"가 아니라 엄선한다 — 넣으면 안 되는 함정**: ① **`ALREADY_PROCESSED_PAYMENT`**("이미 처리된 결제"=**돈 빠짐**)를 거절로 넣으면 **성공 결제를 되돌리는** 바로 그 파국 → 제외(InDoubt). ② 처리오류(`PROVIDER_ERROR`·`CARD_PROCESSING_ERROR`·`FAILED_*`)는 상태 불명 → 제외(InDoubt). ③ 설정 버그(`INVALID_API_KEY`·`UNAUTHORIZED_KEY`·`INVALID_REQUEST` 등)는 사용자 거절이 아니라 우리 버그 → "결제 거절"로 위장하지 않도록 제외(InDoubt로 두고 조사). 즉 목록엔 **"돈 안 빠진 사용자 거절"(카드 거절·한도·잘못된 카드·FDS 차단 등)만** 담는다.

> **`NOT_FOUND_PAYMENT` 포함(2026-07-06 추가) — 위조 키 방어**: "존재하지 않는 결제"(위조·오타 paymentKey)는 결제 객체 자체가 없어 **돈이 빠질 수가 없는 확정 거절**이라 `REJECT_CODES`에 포함한다. 넣지 않으면 confirm이 InDoubt로 접혀 IN_DOUBT 행이 생기고, 이후 해소 조회도 계속 404라 **무한 재시도 + 핫딜 재고 영구 잠금**(악용 벡터)이 된다. 분류 기준("돈 확실히 안 빠진 거절")에 정확히 부합하며, 정상 사용자는 위젯에서 유효 키를 받으므로 이 코드를 볼 일이 없다(정상 결제 오취소 위험 0). 이중 방어로 해소의 `findPayment` 404=`Optional.empty`도 실패 확정(터미널) 처리 — [system 설계](api-design-system.md) 해소 표.

> **실토스 실측(2026-07-01, `TossPayments-Test-Code` 헤더로 코드별 확인)** — 아래는 거절이 아니라 처리오류/상태불명이라 `REJECT_CODES`에 없어 자동 `InDoubt`: `PROVIDER_ERROR`(**400**)·`FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING`·`FAILED_INTERNAL_SYSTEM_PROCESSING`·`UNKNOWN_PAYMENT_ERROR`(500). `PROVIDER_ERROR`가 **400**인데 상태불명인 게 "상태값으로는 못 가른다"는 결정적 근거. `FDS_ERROR`(403, 위험거래 차단)는 돈 안 빠진 차단이라 `REJECT_CODES`에 포함.

> **설계 노트 — 토스 원문 보관**: 해소·CS 증빙용 토스 응답 원문/토스 status는 B1 범위에서 **결과 타입에 최소 식별자(toss status·errorCode)만** 담는다. 수신 원문 누적 테이블(`payment_event`)은 B2 — IN_DOUBT 행에 `pgPaymentKey`(있으면)만 남겨 B2 해소가 토스 조회로 확정할 수 있게 한다.

### B1-3. 멱등 — 토스 멱등키 헤더 + `pgPaymentKey` UNIQUE 충돌

| 멱등 겹 | 위치 | 현재 | B1 |
|---|---|---|---|
| 내부 — 주문당 PAID 1회 | `markPaid` 조건부 전이 | ✅ 동작(affected==0 → 409) | 유지 |
| 외부 — 토스 멱등키 헤더 | `TossHttpClient` 요청 헤더 `Idempotency-Key` | ❌ 미구현 | **신설** |
| 저장 — paymentKey 1건=1행 | `payments.pg_payment_key` UNIQUE | ✅ 제약 존재 | 충돌 처리 추가 |

- **멱등키 = paymentKey**: 별도 UUID를 생성·보관하지 않고 **토스가 발급한 `paymentKey`를 그대로 멱등키로 쓴다**. paymentKey가 "이 결제 시도"의 고유 식별자라 — 같은 결제 재시도(네트워크 재전송)엔 같은 paymentKey → 같은 멱등키 → 토스가 첫 결과 반환(중복 승인 방지), 카드 바꿔 새 결제엔 새 paymentKey → 새 멱등키 → 새 승인 허용. 서버 UUID 발급·주문 행 보관이 **불필요**(Order 변경·마이그레이션 없음). 헤더 `Idempotency-Key: {paymentKey}`로 전송(`@HttpExchange`의 `@RequestHeader`).
- **충돌 시 동작 규약**:
  - 같은 멱등키(paymentKey)로 confirm이 토스에 두 번 도달(네트워크 재전송) → 토스가 **같은 결과**를 반환(토스 멱등 보장) → 이중 출금이 토스 단에서 막힌다.
  - `pgPaymentKey` UNIQUE 충돌(같은 paymentKey로 Payment 이중 저장)은 **`markPaid` 방어(주문당 1회 조건부 전이)로 도달 불가능(unreachable)**하다 — 모든 재시도·동시 경로가 markPaid에서 먼저 409 CONFLICT로 걸려 Payment 저장까지 두 번 가지 않는다. 따라서 충돌 핸들링(catch→기존 반환)은 **죽은 코드 + 삼키기(방어를 뚫은 이상을 숨김)**라 넣지 않는다. UNIQUE 제약은 **최후 정합 안전망**으로 유지하고, 만에 하나 충돌하면 500으로 드러낸다.

> **설계 노트 — 멱등키 칼럼 없음**: 멱등키로 paymentKey를 재사용하므로 별도 `idempotency_key` 칼럼·필드·인덱스는 두지 않는다(`Payment`에 멱등키 칼럼 자체가 없다 — 향후 별도 멱등키 정책이 필요해지면 재검토). 토스 멱등은 `Idempotency-Key: paymentKey` 헤더로, 내부 이중 저장 방지는 `markPaid` 조건부 전이로 각각 담당한다.

> **검토·기각(2026-07-04) — 같은 paymentKey 재요청에 저장 결과 재반환(자기 API 멱등 재응답)**: 응답 유실 후 재시도가 409로 끝나는 것을 "저장된 Payment(DONE/IN_DOUBT) 재반환"으로 바꾸는 안을 구현까지 했다가 기각했다. 이 시스템의 confirm 클라이언트는 결제위젯 리다이렉트 흐름의 브라우저뿐이라, **409(이미 처리된 주문) → 안내 후 주문 내역 확인**이 더 단순하고 충분한 계약이다 — 재응답은 프론트에 "새 승인 vs 재응답" 구분 없는 200 경로를 늘릴 뿐이다. 서버-투-서버 클라이언트나 자동 재시도 계층이 생기면 재검토한다(그때의 구현 방향: markPaid 충돌 시 orderId+paymentKey 로 Payment 조회 → DONE/IN_DOUBT 재반환, 키 불일치·부재는 409 유지).

### B1-4. `TossHttpClient` 신설 규약 (외부 HTTP 1호)

프로젝트에 외부 HTTP 클라이언트 선례가 0건이라 B1이 패턴을 정립한다. 계층은 [ADR-0008](../adr/0008-payment-model-pg-boundary.md) 결정3·4의 3층을 그대로 따른다.

| 층 | 클래스 | 책임 |
|---|---|---|
| 계약 | `PaymentGatewayClient`(인터페이스) | 도메인 언어 — `confirm(paymentKey, orderId, amount) → PgConfirmResult`. 토스 DTO 노출 0(시그니처 불변, 반환 타입만 sealed로 확장) |
| 어댑터 | `TossPaymentClient` | 토스 요청 DTO 조립 → `TossHttpClient` 호출 → 토스 응답/예외를 `Approved`/`Rejected`/`GatewayError`/`InDoubt`로 **분류·매핑**(알려진 결과만 접고 예상 못한 예외는 전파 — 삼키지 않음). 토스 지식 전부 격리 |
| 전송 | `TossHttpClient`(신설, `@HttpExchange` 인터페이스) | 선언적 HTTP — `@PostExchange`로 confirm 호출. Basic 인증·타임아웃은 프록시 빈에, `Idempotency-Key` 헤더는 파라미터로(B2). 판단 로직 0, 얇게 |

- **빈 구성 위치**: `global/config/TossHttpClientConfig`(신규) — `RestClient`(baseUrl·타임아웃·Basic 인증)를 `RestClientAdapter`로 감싸 `HttpServiceProxyFactory`가 `TossHttpClient` 프록시 빈을 생성한다. 전송 엔진은 동기 `RestClient`(starter-web/Undertow, webflux 불필요), 인터페이스는 `@HttpExchange` 선언형.
- **타임아웃 값·근거**: connect **2초**(연결은 빨리 실패해 커넥션 풀 보호 — 못 닿으면 재시도 가능 확정 실패; 토스 미명시라 일반 권장 1~5초 중 채택), read **60초**([토스 공식 타임아웃 가이드](https://docs.tosspayments.com/resources/glossary/timeout) — 결제 처리 API read 60초 권장. 대부분 5초 내 처리되나 카드사·PG 장애 시 지연, 초과 시 **미확정**으로 분류). **TX 분리로 토스 호출이 DB TX 밖**이라 60초 대기해도 커넥션 점유 없음(묶이는 건 Undertow 스레드뿐 — 방식 A였다면 60초 커넥션 점유가 치명적). yaml 외부화로 운영 조정.
- **인증 방식**: 토스 시크릿키를 **HTTP Basic**으로 인코딩 — `Authorization: Basic base64(secretKey + ":")` (토스 규격: 시크릿키를 username, 비밀번호는 빈 문자열). 어댑터/전송층이 시크릿을 코드에 박지 않고 설정에서 주입받는다.
- **설정 배치** — `application.yaml`(신규 `toss` 블록):

```yaml
toss:
  base-url: https://api.tosspayments.com
  secret-key: ${TOSS_SECRET_KEY:test_sk_local-placeholder}   # 환경변수 주입, 기본은 테스트 placeholder
  connect-timeout: PT2S
  read-timeout: PT60S
```

  - `application-test.yaml`: 통합 테스트는 토스 실API를 호출하지 않으므로(대역 유지) `secret-key`에 더미만 둔다. 실HTTP 검증은 `TossHttpClient` 단위 테스트에서 MockWebServer base-url로 덮어쓴다.
  - 시크릿은 환경변수(`${TOSS_SECRET_KEY}`)로 주입 — 코드·git에 평문 시크릿 금지(JWT 시크릿과 동일 패턴).

### B1 ADR 권고

- **[ADR-0008](../adr/0008-payment-model-pg-boundary.md) 갱신 권고**: "트랜잭션 경계 — 단일 TX(토스 호출 포함)" 의도를 **TX 경계 분리(토스 TX 밖 + 거절/오류 보상 + 미확정 IN_DOUBT 보존)**로 교정. 결정3 어댑터 3층의 "상태 불명"이 `PgConfirmResult.InDoubt`로 구체화됨을 반영. (살아 있는 문서 정책상 새 번호 대신 0008 직접 최신화 — [ADR README](../adr/README.md).)
- **신규 ADR 검토(권고만, 작성은 별도)**: IN_DOUBT 상태 도입 + in-doubt 결과 모델은 "외부 결제 미확정을 어떻게 다루나"라는 독립 결정이라 0008 갱신으로 충분한지, 별도 ADR(예: "결제 미확정·해소 모델")이 나은지 backend-architect 판단 권고.
