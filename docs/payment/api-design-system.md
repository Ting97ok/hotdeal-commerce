# 결제 시스템 설계 (Phase B2) — IN_DOUBT 해소

> **해소(resolution)**: 미확정(IN_DOUBT) 결제를 토스에 물어봐 실제 상태(성공/실패)로 **최종 확정**하는 것. (회계 용어 "대사"를 풀어 쓴 표현 — 우리가 하는 건 미확정을 확정으로 바꾸는 것이다.)

> 공통 정의(엔티티·Enum·응답 형식·ExceptionCode·제약)는 [api-design.md](api-design.md) 참조. 유저 결제 승인 흐름은 [api-design-user.md](api-design-user.md).

## 개요

- **배경**: B1에서 confirm 응답이 타임아웃·유실되면 `IN_DOUBT`(결과 미확정)를 남기고 주문은 PAID로 **보존**한다(돈 나갔을 수 있어 롤백 금지). 이 미확정을 **확정**하는 게 B2다.
- **목적(B2 범위)**: **스케줄러가 IN_DOUBT 결제를 주기적으로 결제 조회해 확정하는 해소(polling)**. + IN_PROGRESS(우리 confirm 미완성)는 **멱등 재시도로 매출 복구**. + **PAID 고아**(주문 PAID + Payment 없음 — 아래 절) 스캔 해소.
- **제외(후속)**: **웹훅**(실시간 해소 최적화) — 유실 가능(트래픽·방화벽·다운)해서 정합 backbone이 못 되고, 해소가 보장 backbone이라 **해소를 먼저** 둔다. 웹훅은 그 위의 속도 옵션으로 나중에. **앱 밖 사후 취소/환불/분쟁 반영**(별도 환불 기능)도 제외.

> **왜 웹훅이 아니라 해소가 먼저인가**: 웹훅은 IN_DOUBT을 일으키는 상황(서버 부하·타임아웃)에서 같이 유실될 수 있어, 웹훅만 믿으면 "놓친 IN_DOUBT 영영 미해소" 구멍이 남는다. 스케줄러 폴링은 능동 조회라 놓칠 게 없다. IN_DOUBT은 드물어 폴링 대상이 대부분 0건이라 비용도 낮고, 기존 order 만료 스케줄러와 같은 패턴이라 추가 부담이 작다.

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.3 | 2026-07-04 | **PAID 고아 해소 추가** — "주문 PAID + Payment 없음"(TX1 커밋 후 크래시·배포 재시작 잔여)을 토스 주문번호 조회로 확정. B1 문서가 약속한 "주문 PAID 기준 스캔" 이행 |
| v0.2 | 2026-07-02 | B2 범위 재정의 — 웹훅 → **해소 스케줄러**(결제 조회 폴링)로 전환. IN_PROGRESS는 멱등 재시도로 매출 복구. 웹훅은 후속 최적화로 분리 |
| v0.1 | 2026-07-01 | (초안 — 웹훅 수신 설계. v0.2에서 해소 우선으로 대체) |

## 알려진 제약 (확정 주체는 우리가 아니라 토스)

- **10분 확정은 토스가 한다**: 결제 인증 후 10분 내 confirm이 성립 안 되면 토스가 자동으로 `IN_PROGRESS → EXPIRED`로 바꾼다([코어 API](https://docs.tosspayments.com/reference)). 우리가 "10분 타이머"로 확정하는 게 아니라, **토스가 확정한 상태를 결제 조회로 읽는다**.
- **만료돼도 조회된다**: `EXPIRED`가 돼도 `paymentKey`로 결제 조회 가능(NOT_FOUND 아님, EXPIRED 상태의 Payment 반환). 그래서 폴링이 항상 최종 상태를 읽을 수 있다.
- **보장선**: `IN_PROGRESS`는 영원히 안 남는다 — 토스가 늦어도 10분이면 `DONE` 또는 `EXPIRED`로 만든다. 따라서 스케줄러가 계속 폴링하면 **모든 IN_DOUBT은 최대 10분 안에 확정**된다(보통 1~2분).

## 해소 동작

**스케줄러**(`@Scheduled`, 예: **1분마다**) 매 회차:

1. `IN_DOUBT` 결제 중 **생성 후 grace(예: 1분) 경과**한 것을 조회한다(방금 생긴 건 토스가 아직 처리 중일 수 있어 헛조회 방지).
2. 각 건을 **결제 조회**(`GET /v1/payments/{paymentKey}`)해 실제 status로 분기.

| 결제 조회 status | 의미 | 우리 처리 |
|---|---|---|
| `DONE` | 승인 완료(돈 나감) — confirm이 실제론 성공했고 응답만 유실 | Payment `IN_DOUBT→DONE` 조건부 전이. 주문 PAID·재고 유지 |
| `IN_PROGRESS` | **인증은 됐는데 우리 confirm이 미완성**(돈 안 나감) — 고객은 사려던 상태 | **confirm 멱등 재시도로 완성 시도**(매출 복구). 성공→DONE, 또 미확정→다음 회차, 실패→아래 실패 처리 |
| `EXPIRED` / `ABORTED` / `CANCELED` | 실패·만료 확정(돈 안 나감) | Payment `IN_DOUBT→FAILED` + 주문 `PAID→CANCELED` + **재고 복원**(`restoreSale`) |
| `READY` | 인증 전(비정상 — 우리 IN_DOUBT과 안 맞음) | 변경 없음, 다음 회차/로깅 |

> **설계 노트 — IN_PROGRESS는 "실패"가 아니라 "우리가 마무리 못 한 매출"**: IN_PROGRESS로 남은 건 고객이 인증까지 다 했는데 우리 confirm이 안 들어간(또는 미완성) **우리 쪽 실패**다 — 카드 거절이 아니다. 그래서 만료로 죽이기 전에 **confirm을 멱등 재시도**(멱등키=paymentKey)해 고객이 원한 결제를 **완성**시킨다. 토스가 타임아웃 시 권장한 "멱등 재시도"가 정확히 이 경우다. 재시도는 **10분 창이 자연 상한**(그 뒤엔 EXPIRED라 재시도 대상이 아님)이라 별도 횟수 카운터가 없어도 폭주하지 않는다.

> **설계 노트 — 실패 해소는 PAID→CANCELED(비동기라 재시도 불가)**: B1 동기 보상은 PENDING으로 되돌리지만(유저 즉시 재시도), 해소는 수 분 뒤 비동기 + paymentKey 죽음이라 PENDING이면 좀비 주문이 된다. 그래서 실패 확정은 **CANCELED(종료)** + 재고 복원. 재고는 판매 성립 안 했으니 시점 무관 복원.

> **설계 노트 — 고객 노출 상태는 "확인 중"**: IN_DOUBT은 고객에게 "결제 완료"로 보이면 안 된다(나중에 실패 확정 시 "완료→취소" 민원). Payment가 IN_DOUBT면 주문이 내부적으로 PAID여도 **고객 화면엔 "결제 확인 중"** 으로 노출한다(가상계좌 "입금 대기"와 같은 결). 해소가 확정하면 성공/실패로 갱신 → "완료→취소" 휘둘림이 없다. (이 표현 매핑은 주문 조회 응답의 파생 상태 — 별도 작은 조각.)

## 처리 흐름

```mermaid
flowchart TD
    A[스케줄러 1분] --> B[IN_DOUBT + 경과 grace 조회]
    B --> C{건별 결제 조회}
    C -- DONE --> D[[IN_DOUBT→DONE 조건부 전이<br/>주문 PAID 유지]]:::success
    C -- IN_PROGRESS --> E[confirm 멱등 재시도]
    E --> F{재시도 결과}
    F -- Approved --> D
    F -- 미확정 --> G[/다음 회차 대기/]:::decision
    F -- 실패 --> H
    C -- EXPIRED/ABORTED/CANCELED --> H[[IN_DOUBT→FAILED<br/>주문 PAID→CANCELED · 재고 복원]]:::process
    C -- READY --> G

    classDef error fill:#f8d7da,stroke:#dc3545,color:#dc3545,font-weight:bold
    classDef success fill:#d4edda,stroke:#28a745,color:#155724
    classDef process fill:#d1ecf1,stroke:#17a2b8,color:#0c5460
    classDef decision fill:#fff3cd,stroke:#ffc107,color:#856404
```

## PAID 고아 해소 (v0.3)

> **고아**: TX1 커밋(주문 PAID + 재고 확정) 후 토스 호출·TX2(Payment 저장) 사이에 흐름이 끊겨(프로세스 종료·배포 재시작·예상 밖 예외 전파) 남는 "주문 PAID + Payment 행 없음" 잔여. IN_DOUBT **행** 기준 해소도, PENDING 기준 만료도 못 잡는다 — [B1 공통 정의](api-design.md)가 약속한 "주문 PAID + 결제미완 기준 스캔"이 이 절이다.

같은 해소 스케줄러 회차가 처리한다:

1. **스캔**: `주문 PAID + Payment 부재(NOT EXISTS) + expiresAt + 유예 5분 경과`. 유예를 `expiresAt` 기준으로 두는 이유 — markPaid는 PENDING(=expiresAt 전)에만 성공하므로 confirm 시작은 늦어도 expiresAt 이전이고, 토스 read 60초 + TX2를 감안해도 5분이면 정상 흐름은 끝나 있다(진행 중 confirm을 고아로 오인할 수 없음). 스캔은 결제 장부의 구멍 찾기라 payment 도메인이 소유하고(order는 Payment의 존재를 모른다 — 의존 방향 payment→order 유지), `PaymentRepository`가 주문 엔티티를 그대로 반환한다. 후보를 order만으로 뽑는 2단 구성은 불가 — "PAID+만료 경과"는 역대 완료 주문 전부라 부재 판정(NOT EXISTS)이 DB 안에 있어야 한다.

> **알고 있는 표준 이탈 — 리포지터리가 타 애그리거트(Order)를 반환**: DDD 표준(애그리거트당 리포지터리 1:1, 자기 루트만 반환 — Vernon의 교차 조회는 값/프로젝션)에서 벗어난 선택이다. ID 프로젝션 + 건별 재조회 형태도 검토했으나 기각 — ① JPQL이 어차피 Order 엔티티를 참조하므로 결합은 프로젝션으로 제거되지 않고, ② 이 코드베이스의 상태 전이는 전부 조건부 UPDATE(서비스 경유)라 반환 엔티티로 애그리거트 불변식을 우회할 경로가 없으며(1:1 규칙의 실질 목적이 이미 다른 수단으로 충족), ③ 남는 차이는 고아당 +1 조회와 우회 한 겹뿐이라 JPA 엔티티 활용이라는 이 저장소의 기조에 맞게 직접 반환을 택했다.
2. **토스 주문번호 조회**(`GET /v1/payments/orders/{orderId}` — 고아는 paymentKey가 없으므로 orderNo가 유일한 열쇠)로 분기:

| 조회 결과 | 처리 |
|---|---|
| `DONE` + 금액 = 주문 금액 | Payment DONE 생성 — **매출 복구**, 주문 PAID 유지 |
| `DONE`인데 금액 불일치 | 자동 확정하지 않고 warn → 다음 회차(수동 확인) — 어긋난 돈을 자동 확정하지 않는다 |
| `EXPIRED`/`ABORTED`/`CANCELED` 또는 **404(결제 없음)** | 주문 `CANCELED(PAYMENT_FAILED)` + 핫딜·상품 재고 방출 |
| 그 외(IN_PROGRESS 등) | 다음 회차 — IN_PROGRESS는 토스 10분 자동 EXPIRED가 자연 종결 |

> **404를 실패 확정해도 안전한 이유**: 매입(돈 빠짐)은 우리 confirm 호출로만 일어나고, confirm이 성립했다면 토스에 결제가 존재한다(404 아님). 즉 404 = 매입 미발생. 취소 후 지연 도착한 confirm 재시도는 `markPaid` 관문(affected 0)이 토스 호출 전에 차단하므로, **취소 이후 돈이 나갈 경로가 없다**.

> **불변식 — 후속 처리 트리거는 Payment DONE**: 배송·알림·정산 같은 후속 처리는 Order.PAID(승인 선점 상태)가 아니라 **Payment DONE(결제 확정)** 을 트리거로 삼는다. 이 불변식이 지켜지는 한 고아의 자동 취소는 후속 피해가 없고, 부분 MSA 분리 시 발행할 이벤트도 "결제 확정"이지 "주문 PAID"가 아니다.

## 테스트 리스트

| # | 테스트 케이스 | 시나리오 | 상태 | 작성일 |
|---|---|---|---|---|
| 1 | `해소_DONE_확정` | 결제 조회가 DONE → IN_DOUBT을 DONE으로 확정 | ✅ Pass | 2026-07-02 |
| 2 | `해소_실패_확정_재고복원` | 결제 조회가 EXPIRED → Payment FAILED·주문 CANCELED·핫딜+상품 재고 복원 | ✅ Pass | 2026-07-02 |
| 3 | `해소_grace_미경과_제외` | 생성 후 grace(1분) 미경과 IN_DOUBT은 해소 대상에서 제외 → IN_DOUBT 유지 | ✅ Pass | 2026-07-02 |
| 4 | `해소_스케줄러_배선` | 스케줄러 활성화 시 PaymentResolutionScheduler 빈 생성·컨텍스트 로드 | ✅ Pass | 2026-07-02 |
| 5 | `해소_IN_PROGRESS_재시도_승인` | 결제 조회가 IN_PROGRESS → confirm 멱등 재시도 Approved → DONE 확정 | ✅ Pass | 2026-07-02 |
| 6 | `해소_IN_PROGRESS_재시도_거절` | confirm 재시도 Rejected → Payment FAILED·주문 CANCELED·핫딜+상품 재고 복원 | ✅ Pass | 2026-07-02 |
| 7 | `해소_IN_PROGRESS_재시도_미확정_유지` | confirm 재시도 GatewayError(미확정) → IN_DOUBT·주문 PAID 유지, 다음 회차 대기 | ✅ Pass | 2026-07-02 |
| 8 | `해소_1건_조회실패_나머지_계속` | 한 건의 토스 조회가 예외(4xx/5xx·통신)로 실패해도 그 건만 warn 로그 후 스킵, 나머지 IN_DOUBT은 해소 — 독성 1건이 파이프라인을 못 막음 | ✅ Pass | 2026-07-04 |
| 9 | `고아_DONE_매출복구` | PAID+Payment 없음(유예 경과) → 토스 주문번호 조회 DONE·금액 일치 → Payment DONE 생성, 주문 PAID 유지 | ✅ Pass | 2026-07-04 |
| 10 | `고아_404_실패확정` | 토스에 결제 없음(404 = 매입 미발생) → 주문 CANCELED(PAYMENT_FAILED)·핫딜+상품 재고 방출 | ✅ Pass | 2026-07-04 |
| 11 | `고아_EXPIRED_실패확정` | 토스 실패 상태 → 동일 실패 확정 | ✅ Pass | 2026-07-04 |
| 12 | `고아_유예_미경과_제외` | expiresAt+5분 전이면 스캔 제외(진행 중 confirm 오인 방지) — 토스 미호출 | ✅ Pass | 2026-07-04 |
| 13 | `고아_IN_PROGRESS_다음회차` | 미확정 상태면 확정하지 않고 유지(토스 10분 자동 EXPIRED가 자연 종결) | ✅ Pass | 2026-07-04 |
| 14 | `고아_금액불일치_확정보류` | DONE이어도 토스 금액 ≠ 주문 금액이면 자동 확정하지 않고 warn(수동 확인) | ✅ Pass | 2026-07-04 |
| 15 | `스케줄러_양쪽_해소_호출` | runResolution이 IN_DOUBT 해소와 PAID 고아 해소를 모두 호출 | ✅ Pass | 2026-07-04 |
| 16 | `토스_주문번호조회_DONE_매핑` | (단위) `GET /v1/payments/orders/{orderId}` 200 → `PgPayment`(paymentKey 포함) 매핑 | ✅ Pass | 2026-07-04 |
| 17 | `토스_주문번호조회_404_빈결과` | (단위) 404(결제 없음) → `Optional.empty` — 매입 미발생 신호 | ✅ Pass | 2026-07-04 |

## 계층

| 층 | 클래스 | 책임 |
|---|---|---|
| 스케줄러 | `PaymentResolutionScheduler` | `@Scheduled` — IN_DOUBT 목록 조회 후 건별 Facade 위임(로직 0). order 만료 스케줄러와 같은 패턴 |
| Facade | `PaymentResolutionFacade` | 건별: 결제 조회 → status 분기 → 확정/재시도/실패 조합(Payment·Order·Stock). 메서드 `@Transactional` |
| Service | `CommonPaymentService`(전이)·`CommonOrderService`(PAID→CANCELED)·`ProductStockService`(restoreSale) | 자기 도메인 조건부 전이 |
| 게이트웨이 | `PaymentGatewayClient.getPayment(paymentKey)` + `findPaymentByOrderId(orderId)`(v0.3) + `confirm(...)`(B1 재사용) → `TossPaymentClient` → `TossHttpClient` | 결제 조회 GET 2종(paymentKey·주문번호), IN_PROGRESS 재시도는 기존 confirm 재사용 |

### 결제 조회 게이트웨이 규약 (신설)

B1의 3층(계약/어댑터/전송)을 확장한다.

```java
// 계약 — 실제 상태 재확인 (paymentKey 기준 + 주문번호 기준·v0.3, 404=빈 결과)
PgPayment getPayment(String paymentKey);
Optional<PgPayment> findPaymentByOrderId(String orderId);

// 결과 — 토스가 확정한 상태 최소 식별자 (paymentKey는 고아 복구의 Payment 행 생성에 필요·v0.3)
record PgPayment(String paymentKey, PgPaymentStatus status, BigDecimal totalAmount, LocalDateTime approvedAt) {}
enum PgPaymentStatus { DONE, IN_PROGRESS, EXPIRED, ABORTED, CANCELED, READY, WAITING_FOR_DEPOSIT, UNKNOWN }

// 전송 — @HttpExchange
@GetExchange("/v1/payments/{paymentKey}")
TossConfirmResponse getPayment(@PathVariable String paymentKey);   // 조회 응답은 confirm과 같은 Payment 객체라 재사용
@GetExchange("/v1/payments/orders/{orderId}")
TossConfirmResponse getPaymentByOrderId(@PathVariable String orderId);
```

## 쿼리 설계

```java
// IN_DOUBT + 생성 후 grace 경과 (조건부 조회, 페이지/배치)
@Query("""
    SELECT p FROM Payment p
    WHERE p.status = 'IN_DOUBT' AND p.createdAt <= :threshold
""")
List<Payment> findInDoubtBefore(@Param("threshold") LocalDateTime threshold);
```

## 후속 (B3+)

- **웹훅**: 해소보다 **빠른** 실시간 해소가 필요해지면 `PAYMENT_STATUS_CHANGED` 웹훅을 붙여 같은 확정 로직(결제 조회 재확인 → status 분기)을 재사용한다. 웹훅은 **해소를 대체하지 않고**(유실 가능) 그 위의 최적화. 검증은 서명이 아니라 결제 조회 재확인.
- ~~**넓은 해소**~~ → **구현됨(v0.3)**: "주문 PAID + Payment 없음" 스캔은 본문 "PAID 고아 해소" 절로 이행. 일 단위 PG **전체 대조**(전 결제 건 대조 — 배민식)만 재무급 안전망으로 후속에 남는다.
- **앱 밖 사후 변경**: CS·카드사 취소, 분쟁(chargeback)은 환불/취소 기능과 함께 웹훅으로 반영.
