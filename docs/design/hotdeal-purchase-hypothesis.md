# 핫딜 선착순 구매 — 기술 가설 (확정)

> **[가설 PRD](hotdeal-prd.md)의 기술 번역** — PRD 의 요구·정책을 어떤 기술 규칙으로 지키는지 정의한다.
> 작성: 2026-06-10 · 근거 자료: [research-flash-sale.md](research-flash-sale.md) · 데이터 모델: [erd.md](erd.md) · **결정의 이유(대안·트레이드오프)는 전부 [ADR](../adr/README.md)** — 본 문서는 결정된 규칙만 적는다.
> 표기: 기술 용어는 첫 등장에 한 줄 풀이를 병기한다.

## 0. 한 줄 요약

서버 여러 대로 운영되는 Spring 환경에서, 핫딜 한정 재고를 **오버셀(oversell — 재고보다 많이 팔리는 일) 0건**으로 차감하고 토스 결제까지 깨지지 않게 잇는 선착순 구매. **"적은 API + 깊이."**

## 1. 증명하려는 것

- **쓰기 동시성**(여러 요청이 같은 데이터를 동시에 고치려는 상황) — 주문 폭주 속 오버셀 0건
- **주문–결제 정합성**(관련 데이터가 서로 모순 없이 맞아떨어지는 상태) — 결제 실패 시 되돌리기(보상), 중복 요청 안전

## 2. 범위

| 구분 | 내용 |
|---|---|
| 구매 | **상품 주소 API** (`POST /api/orders {productId}` — 서버가 활성 핫딜 해소) — 주문은 상품(`product`)+적용 핫딜 참조, 재고 원본은 `ProductStock` ([ADR-0011](../adr/0011-product-inventory-reservation.md)) |
| 가설 | **결제까지 완결** — 구현만 슬라이스(작업을 세로로 쪼갠 단위, API 하나 정도)로 나눠 쌓음 |
| 본편 제외 | 대기열·봇/다계정 어뷰징(부정 구매) 방어 · 고트래픽 조회(4주차) · **승인 후 환불**(결제 전 이탈은 시간 초과 취소가 커버) · **주문 내역 조회·"이어서 결제"**(만료 후 재구매로 갈음) · 다중 PG 일반화 |

## 3. 배포 전제

- **서버 여러 대** (로드밸런서 뒤, MySQL·Redis 공유)
- → `synchronized` 같은 **JVM 안 잠금은 서버 1대 안에서만 통함** → 유효한 수단은 DB 잠금 · Redis · 분산락(여러 서버가 공유하는 저장소에 두는 잠금)뿐

## 4. 꼭 지켜야 하는 규칙 — 불변식(어떤 순간에도 항상 성립해야 하는 규칙)

1. **오버셀 0건 + 거짓 성공 0건(성공 응답인데 실제 주문 없음) — 절대 불변식. 덜 팔림(재고가 남았는데 경합 탓에 실패)은 허용** — "다시 시도" 응답으로 정직하게 반환, 서버가 몰래 재시도하지 않음. → [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md)
2. **1인 구매 제한** — 계정당 살아 있는 주문(PENDING/PAID) 1건만(+ 주문당 `maxPerOrder`, 총량 `maxPerAccount`는 결제단계), 취소 후 재구매 허용. 계정당 1활성주문은 DB 유니크 제약(생성 칼럼)으로 강제 — 중복 클릭·재시도·결제창 다중 오픈까지 차단(내부 멱등 겸용). → [ADR-0005](../adr/0005-one-per-user-active-unique.md)
3. **주문 시 재고 선점 + 복원은 정확히 한 번** — 취소 전이("PENDING 일 때만 CANCELED 로" 조건부 갱신)가 성공한 그 한 번에만 같은 트랜잭션에서 복원, `cancel_reason` 기록. → [ADR-0004](../adr/0004-stock-reservation-lifecycle.md)
4. **금액 조작 방지** — 주문 시점 금액 저장(특가×수량), 결제 검증은 서버가 저장된 금액으로.
5. **장부 일치(정합 검증식)** — `총 수량 = 남은 재고 + 살아 있는 주문 수량 합` · `주문당 승인 결제 ≤ 1` · `PAID 주문 ↔ 승인 결제 1:1`. 모든 동시성 테스트의 마지막 단언. → [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md)

**방어 계층 분업** — 애플리케이션 검증(싸고 사유 있는 거절+실패 폭주 거름) / DB 원자성·유니크(동시성 최종 직렬화) / CHECK(버그 보험) / 검증식(설계 결함 탐지). 각 층은 다른 층이 못 하는 일을 맡으며, 층별 비용-효용으로 취사한다. → [ADR-0006](../adr/0006-correctness-invariants-defense-layers.md)

**식별자 정책** — 공개 카탈로그(상품·핫딜) = 순번 id 노출 / 민감(주문) = 불투명 `order_no`(UUID — 추측 불가능한 무작위 식별자, 토스 주문번호 겸용) / 결제 = 토스 paymentKey. → [ADR 인덱스 — 식별자 정책](../adr/README.md)

## 5. 구매 흐름 — 선점 기준은 "주문 생성 API 성공" ([ADR-0004](../adr/0004-stock-reservation-lifecycle.md))

```
[화면] 핫딜 상세·주문서 진입         → 아무것도 선점되지 않음
[화면] "구매하기" 클릭
[API]  주문 생성 성공                → ★ 선점: 재고 차감 + PENDING + order_no(UUID) + 만료시각
[화면] 토스 결제창 (만료시각 안에서 진행)
[API]  결제 승인 (토스 호출은 DB 트랜잭션 밖)
        성공 → PENDING→PAID 전이 (1회만 성공)
        재시도 무의미 실패·시간 초과 → PENDING→CANCELED 전이 + 재고 복원 (전이 성공 시에만)
        재시도 가능 실패(한도 초과 등) → 제한시간 내 같은 주문으로 재시도 (방향 — 슬라이스 3 확정)
```

- **멱등**(같은 요청이 여러 번 와도 결과는 한 번 처리한 것과 같음) 2겹: 내부 = 계정당 1활성주문 유니크 / 외부 = 토스 멱등키. → [ADR-0008](../adr/0008-payment-model-pg-boundary.md)

## 6. 핫딜 상태 · 관리자 운영 정책 ([ADR-0007](../adr/0007-hotdeal-state-operations.md))

- 진행/매진은 **판매 기간(startAt~endAt)과 남은 재고로 그때그때 판단** — 진행 상태값·스케줄러 없음 (정시 오픈 = 시작 시각 도달). `status {ACTIVE, CANCELED}` 는 관리자 취소 전용.
- **취소 시**: 새 구매와 결제 승인 모두 차단(승인 검사는 토스 호출 전) · 기존 PAID 주문은 유효(환불은 토스 대시보드 수동) · PENDING 은 만료로 자연 취소.
- **운영 중 수정·수량 변경 API 없음** — 변경은 취소 후 재등록, 추가 물량은 새 핫딜(회차) 등록. `totalQuantity` 등록 후 불변. (스코프 선택 — 재입고 증량의 실무 실재와 전환 경로는 [ADR-0007](../adr/0007-hotdeal-state-operations.md))
- **같은 상품에 판매 기간 겹치는 핫딜 등록 불가** — 관리자 등록 API 검증.
- 운영 현황·집계는 DB 쿼리(정합 검증식 재사용)·토스 대시보드로 갈음. 매진 품절 표시는 선택 사항(표시는 캐시일 뿐, 판단 원본은 재고).

## 7. 거부 케이스 → 그대로 테스트가 됨

| 케이스 | 응답(예시) |
|---|---|
| 핫딜 없음 | `HOTDEAL_NOT_FOUND` (404) |
| 판매 기간 밖 | `HOTDEAL_NOT_OPEN` (400) |
| 취소된 핫딜 구매 | `HOTDEAL_CANCELED` (400) |
| 재고 부족/매진 | `OUT_OF_STOCK` (409) |
| 이미 구매함 | `ALREADY_PURCHASED` (409) |
| 동시 경합에 밀림(재고는 남음) | `CONCURRENT_UPDATE_CONFLICT` (409, "다시 시도") |
| (결제 승인) 취소된 핫딜의 승인 시도 | `HOTDEAL_CANCELED` (400) — 검사는 토스 호출 전 |
| (결제 승인) 만료된 주문의 승인 시도 | `ORDER_EXPIRED` (409) |
| (결제 승인) 금액 불일치 | `AMOUNT_MISMATCH` (400) |

## 8. 동시성 — 5방식 벤치마크 + 잠금 규율 ([ADR-0009](../adr/0009-stock-concurrency-design.md))

- 후보 5방식: **낙관락**(@Version — 잠그지 않고 진행, 저장 순간 버전 비교로 충돌 검출) · **비관락**(SELECT … FOR UPDATE — 먼저 잠그고 뒤는 대기) · **Redis 원자 연산** · **분산락**(Redisson) · **원자적 조건부 UPDATE**(`WHERE remaining >= qty` 조건부 차감 한 문장 — ProductStock 운영 채택 [ADR-0011](../adr/0011-product-inventory-reservation.md) 결정 4). **운영 선택은 1개, 나머지는 그 선택의 근거** — README/ADR-0010 에 그렇게 표기. `synchronized` 같은 JVM 잠금은 **후보가 아니다** — 서버 1대 안에서만 직렬화돼 다중 서버 경합을 못 막는다(3장 전제 · 상세 [ADR-0009](../adr/0009-stock-concurrency-design.md), 별도 시연 없이 문서로 확정).
- 비관락 대기는 **즉시 실패(NOWAIT)가 출발값** (비교군 1초 — 3주차 실측).
- **잠금 순서**: 주문·재고 두 테이블을 건드리는 모든 경로는 "주문 → 재고" 순서로 통일. 만료 일괄 처리는 주문 1건 = 트랜잭션 1개. 교착(deadlock — 서로의 잠금을 기다리는 고리, DB 가 즉시 한쪽을 강제 취소) 시: 구매 = `CONCURRENT_UPDATE_CONFLICT` / 만료 = 다음 주기 재시도. 비관락 벤치마크는 교착 허용 + 횟수 측정.
- 방식별 "성공 수"는 비교 지표(낙관락 < 10, 비관락/Redis = 10 — 같은 동시 100·재고 10 기준).

## 9. 만드는 순서 — 어느 시점에 멈춰도 시스템이 자기 완결

| # | 슬라이스 | 핵심 | 설계 노트 |
|---|---|---|---|
| **기반** | 엔티티 + V2 마이그레이션 | User(기존)·**Product·ProductStock**·HotDeal·HotDealStock·Order·Payment 매핑 + Flyway V2 | Product·ProductStock(재고 원본)는 핫딜이 참조·예약해 **함께 생성**(on_hand 시드). FK 제약 없음·활성 유니크 생성 칼럼 등 [ERD 6](erd.md) |
| **0** | 핫딜 등록(관리자) + 조회 | 등록 시 ProductStock 가용 검사·예약 + HotDealStock(1:1) 동시 생성 · 기간 겹침 검증 | 수정·수량 변경 API 없음 — [ADR-0007](../adr/0007-hotdeal-state-operations.md)·[0011](../adr/0011-product-inventory-reservation.md) |
| **1** | 구매 — 재고 선점 차감 + 주문 PENDING | 오버셀 0건 (낙관락부터) | 상품 주소 `POST /api/orders {productId}`(서버가 활성 핫딜 해소) · 주문은 상품+핫딜 참조 · HotDealStock 한 행 차감 · 만료시각 부여(임시 10분) · 활성 유니크 · order_no · 정합 검증식 단언 |
| **2** | 만료 복원 — 시간 지난 PENDING 자동 취소 | 조건부 전이(사유 EXPIRED) + 재고 복원 | 주문 1건=트랜잭션 1 · 잠금 순서 "주문→재고" · **이 시점부터 선점↔해제가 닫혀 상시 자기 완결** |
| **3** | 토스 결제 승인 + PAID 확정 / 실패 CANCELED | 토스 호출 분리 · 금액 재검증 · 멱등 | 승인 가드(만료 주문·취소 핫딜 거부) · 이중 승인 방어 · 만료 처리에 토스 조회 보조 추가(결제됨 발견 = PAID 확정) · 어댑터 구조(PaymentGatewayClient) · **결제 동시성 테스트**: 가짜 대역에 지연·중복 승인·응답 유실 주입으로 이중 승인·만료↔결제 경합 재현(외부 부하 없이) — [ADR-0004](../adr/0004-stock-reservation-lifecycle.md)·[ADR-0008](../adr/0008-payment-model-pg-boundary.md) |
| 3주차 | 5방식 비교 + k6(대량 가상 트래픽을 쏘는 부하 테스트 도구) | 성능/정확성 정량화 | 즉시 실패(NOWAIT) vs 1초 · 교착 횟수 측정 — [ADR-0009](../adr/0009-stock-concurrency-design.md), 결과는 ADR-0010 로 |
| 이후 | 대기열(분산/엣지) · 고트래픽 조회 | 본편 완수 후 | |

## 10. 엔티티 (7개)

**User · Product · ProductStock · HotDeal · HotDealStock · Order · Payment** — Product 가 재고 원본(`ProductStock` 실물·예약), 핫딜은 거기서 예약([ADR-0011](../adr/0011-product-inventory-reservation.md)). Order : Payment = **1 : N**, Payment 행 단위 = **paymentKey 1개** ([ADR-0008](../adr/0008-payment-model-pg-boundary.md)). 관계·제약은 [erd.md](erd.md).

## 11. 남은 일 (보류 항목)

- 만료시각 최종값(임시 10분) · 판매 종료와 결제 허용의 관계 · 만료 처리 방식(스케줄러 vs Redis TTL) · 다중 서버의 만료 작업 중복 실행(방향: 중복 허용 — 안전은 "전이 1회"가 보장) — 슬라이스 2 에서, [ADR-0004 보류](../adr/0004-stock-reservation-lifecycle.md).
- 비관락 대기 설정·교착 횟수 — 3주차 실측 → ADR-0010.
- 탈퇴 기능 도입 시 주문 경로의 사용자 상태 검증 재검토 — 지금은 탈퇴 자체가 없어 JWT 인증 통과 = 실존 보장.
- 승인 실패 분류별 주문 처리 확정(재시도 가능 = 제한시간 내 PENDING 유지 / 무의미 = 즉시 취소+복원) — 슬라이스 3, [ADR-0008](../adr/0008-payment-model-pg-boundary.md).
- 웹훅(토스가 우리 서버로 결제 결과를 직접 보내 주는 통보) 보정 상세 — 슬라이스 3 진입 때 추가 리서치로 보강.
