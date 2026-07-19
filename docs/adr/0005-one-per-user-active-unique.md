# ADR-0005. 1인 구매 제한 — 계정당 1활성주문 · 주문당/총량 상한 (단계별)

- 상태: 확정 · 작성: 2026-06-11 · 갱신: 2026-06-20 ("1인 1개" → 다층 제한 정책으로 확장 — 주문당 `maxPerOrder` · 총량 `maxPerAccount`, 슬라이스별 구현)
- 관련 규칙: [가설 4-2](../design/hotdeal-purchase-hypothesis.md) · [erd 6](../design/erd.md) · 구매 제한 조사: 본문 하단

## 결정 요약

- **무엇을**: 1인 구매 제한을 **세 겹**으로 — 계정당 1활성주문(활성 유니크 생성 칼럼) + 주문당 `maxPerOrder` + 총량 `maxPerAccount`(③결제단계). 정책은 지금 전부 정의, 코드는 슬라이스별로.
- **왜**: 동시 중복 클릭·재시도·결제창 다중 오픈은 **DB 활성 유니크로만** 직렬화 가능 — 앱 검증("이미 샀나?" 조회 후 INSERT)은 확인↔쓰기 사이 끼어듦에 원리적으로 무력.
- **버린 대안**: 앱 검증만(TOCTOU 무력) / 완전 유니크(취소 후 재구매 불허) / 별도 보조 테이블(동기화 코드 증가) / PostgreSQL 부분 유니크 인덱스(MySQL에 없음 — 생성 칼럼이 그 우회 표준)
- **범위 밖(Non-Goal)**: 봇/다계정 어뷰징 방어(수량 제한과 별개 레이어), 총량 상한 코드 구현(정책만 정의 — 결제 슬라이스에서)
- **트레이드오프**: 얻음 = 동시 중복 최종 차단 + 내부 멱등 겸용(별도 멱등키 칼럼 불필요) ↔ 잃음 = 생성 칼럼 우회(MySQL 제약 — PostgreSQL 이행 시 부분 유니크로 단순화) · 총량 상한 도입 시 메커니즘 교체
- **가역성**: **Type1**(스키마 결정)이나 **비대칭** — 인덱스 추가가 비싸지 삭제는 쌈(`DROP INDEX`는 MySQL 8 온라인 DDL). 엔티티 비매핑이라 DB 이행 시 자바 코드 0줄.

## 컨텍스트

"한 사용자가 한 핫딜을 얼마나 살 수 있나"를 어디서 어떻게 강제하나. 동시 중복 클릭·재시도·결제창 다중 오픈까지 막아야 한다. 실제 커머스의 1인 구매 제한은 한 겹이 아니라 **세 겹**이다(조사 — 하단):

1. **계정당 동시 주문 1건** — 중복 클릭·재시도 차단.
2. **주문당 수량 상한** — 한 주문에 최대 N개(핫딜이 설정).
3. **계정당 총량 상한** — 여러 번 나눠 사도 합쳐서 최대 M개(핫딜이 설정).

이 셋은 강제 시점·메커니즘이 달라 한꺼번에 만들 수 없다 — 구매→만료→결제 슬라이스를 따라 자란다. **정책은 본 ADR 이 전부 정의하고, 코드는 해당 슬라이스에서 구현**한다([commit-checkpoint](../../.claude/rules/commit-checkpoint.md) — 미래 시나리오는 설계 문서에, 코드는 미리 짜놓지 않음).

## 결정 1 — 계정당 1활성주문 = 활성 유니크 (생성 칼럼)

`orders` 에 저장 생성 칼럼(stored generated column — 다른 칼럼 값으로 DB 가 자동 계산·저장하는 칼럼)을 두고 복합 유니크를 건다:

```sql
is_active TINYINT GENERATED ALWAYS AS (IF(status IN ('PENDING','PAID'), 1, NULL)) STORED,
UNIQUE KEY uk_orders_active (user_id, hot_deal_id, is_active)
```

유니크 제약은 NULL 을 유일성 검사에서 제외한다 — NULL 은 "값이 없음"이라 서로 비교되지 않기 때문이다(SQL 표준 — MySQL·PostgreSQL 동일, 예외는 SQL Server). 따라서 취소 주문(is_active=NULL)은 무제한, 살아 있는 주문만 1건. 칼럼은 **엔티티에 매핑하지 않는다**(DDL 전용 — JPA 가 생성 칼럼에 쓰기를 시도하는 함정 회피). 위반은 `ALREADY_PURCHASED` 로 매핑.

이 유니크는 "수량"이 아니라 **살아 있는 주문 건수 1** 을 강제한다 — 한 사람이 한 핫딜에 미결제/결제 주문을 동시에 1건만 갖는다. 수량 제한(결정 2·3)은 이 위에 얹는 **별개 층**이다.

## 결정 2 — 주문당 수량 상한 = 핫딜별 `maxPerOrder` (① 구매 단계)

한 주문에 담는 수량은 핫딜이 정한 `maxPerOrder` 까지다. `orders.quantity` 칼럼은 **이미 존재**하므로(현재 1 고정) orders 스키마 변경 없이 정책만 연다:

- 핫딜 등록 시 `maxPerOrder`(1주문 최대 수량) 설정 — `hot_deals` 칼럼 신규(슬라이스0 소급).
- 구매 시 `quantity ≤ maxPerOrder` 검증(초과 시 `EXCEEDS_PURCHASE_LIMIT`), `order_amount = dealPrice × quantity`.
- `HotDealStock` 차감도 `quantity` 단위(오버셀 0 검증이 수량 단위로).

## 결정 3 — 계정당 총량 상한 = 핫딜별 `maxPerAccount` (③ 결제 단계)

한 사람이 **여러 번 나눠** 사도 결제 완료(PAID) 누적이 `maxPerAccount` 를 못 넘는다.

- **여러 번이 성립하려면 첫 주문이 끝나야**(만료/결제) 둘째 주문이 가능하다 → 만료(②)·결제(③) 흐름이 전제다. 그 전(① 구매만)엔 계정당 1활성주문이라 누적이 **발생하지 않는다**.
- 총량을 켜면 `is_active` 를 **PENDING 만**으로 재정의(PAID 무제한)하고, 주문 생성 시 "PAID 누적 + 신규 `quantity` ≤ `maxPerAccount`" 를 검증한다 — 이는 유니크로 표현 못 하는 카운트라 **잠금 하 검증 또는 카운터**로 바뀐다(아래 전환 경로).
- 동시성 난제(누적합 경합)는 이 단계에서 온전히 다룬다.

## 단계별 구현 (정책은 지금 전부 정의, 코드는 슬라이스로)

| 단계 | 제한 | 메커니즘 | 근거 |
|---|---|---|---|
| **① 구매**(슬라이스1) | 계정당 1활성주문 + `maxPerOrder` | `uk_orders_active` + `quantity` 검증 | 결정 1·2 |
| **② 만료**(슬라이스2) | 만료 시 활성 해제 + 쿼터 복원 정책 | `is_active`→NULL, 복원 여부 결정 | [ADR-0004](0004-stock-reservation-lifecycle.md) |
| **③ 결제**(슬라이스3) | `maxPerAccount` 누적 | `is_active` 재정의 + PAID 누적 검증 | 결정 3 |

## 대안 비교 (결정 1)

| 대안 | 평가 |
|---|---|
| 앱 검증만 ("이미 샀나?" 조회 후 INSERT) | 확인과 쓰기 사이에 끼어드는 동시 요청을 원리적으로 못 막음 — 기각 |
| 완전 유니크 (user_id, hot_deal_id) | 취소 후 재구매 불허가 돼 요구사항 위반 — 기각 |
| 별도 보조 테이블 (활성 구매 행 INSERT/DELETE) | 실무에 흔한 정당한 대안. 다만 테이블+동기화 코드가 늘어 미채택 |
| PostgreSQL 부분 유니크 인덱스 | MySQL 에 없음 — 생성 칼럼이 그 우회 표준 |

## 부가 효용 — 유니크 = 내부 멱등 장치

결정 1 의 유니크 하나가 중복 클릭·네트워크 재시도·결제창 다중 오픈을 전부 차단한다 — 별도 멱등키 칼럼 없이 내부 멱등이 해결된다(외부 멱등은 토스 멱등키 — [ADR-0008](0008-payment-model-pg-boundary.md)).

## 전환 경로 — 결정 3 도입 시 메커니즘 교체

- 유니크는 "최대 N"을 표현할 수 없으므로, 총량 누적(결정 3)으로 가면 **메커니즘 교체**가 필요하다: `is_active` 를 PENDING 만으로 재정의 + 잠금 하 카운트 검증(또는 `(user, deal, slot_no)` 슬롯, 또는 카운터).
- 그 교체 비용은 **지금 유니크를 걸든 안 걸든 동일**하다 — 미리 안 걸어도 전환이 싸지지 않고, 현재의 보호(중복 차단)만 잃는다.
- 운영 중 제거 부담도 낮다: `DROP INDEX` 는 MySQL 8 온라인 DDL(테이블 재구축 없음, 진행 중 읽기/쓰기 허용). 비싼 건 인덱스 추가지 삭제가 아니다.
- **DB 이행 경로**: PostgreSQL 로 옮기면 생성 칼럼 우회 자체가 불필요 — 부분 유니크 인덱스(`CREATE UNIQUE INDEX … WHERE status IN ('PENDING','PAID')`)로 교체되어 오히려 단순해진다. 집행 수단이 마이그레이션 DDL 한 곳에 격리돼 있고 엔티티 비매핑이라 자바 코드 변경은 0줄.

→ 결론: **현재 확정 정책(① 계정당 1주문 + maxPerOrder)은 DB·검증이 강제하고, 총량(③)의 전환 메커니즘은 문서로 들고 있는다** — "성급한 결정"이 아니라 "전환 경로를 아는 결정".

## 출처

- 실무 실재: 우아한형제들 재고 시스템 "인당 구매제한수량", 알리 타임딜 "1인당 1일 1개" — [리서치 10.2](../design/research-flash-sale.md)
- 구매 제한 조사(2026-06-20): 핫딜/플래시세일의 1인 제한은 "계정당 1회 + 주문당 상한"이 표준, **카운트 기준은 주문 생성 시점**(결제 전 선점), 총량 누적은 소수(고가 한정판), 취소·만료 시 재고는 복원하되 1인 쿼터는 잠금 유지가 어뷰징 방지 관행, 어뷰징(다계정) 방어는 수량 제한과 별개 레이어
  - [Flash Sale System Design — Ajit Singh](https://singhajit.com/flash-sale-system-design/)
  - [Flash sale strategies & guardrails — Voucherify](https://www.voucherify.io/blog/flash-sale-definition-and-examples)
  - [Shopify — Limit quantity per customer](https://help.shopify.com/en/manual/sell-online/flash-sales)
