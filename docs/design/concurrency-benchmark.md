# 동시성 벤치마크 설계 — 재고 차감 3방식

> 핫딜 재고 차감(`HotDealStock`)을 3방식으로 **동일 조건 측정**해 운영 1개를 고르는 벤치마크의 구현 설계.
> **결정 정본은 ADR** — 측정 대상·지표는 [ADR-0009](../adr/0009-stock-concurrency-design.md), 부하 도구는 [ADR-0013](../adr/0013-load-test-tool-k6.md), 결과는 [ADR-0010(예정)]. 본 문서는 그 결정을 **어떻게 구현·측정하는지**를 정의한다.
> 근거 자료: [research-flash-sale.md](research-flash-sale.md)

## 0. 범위

- **측정 대상**: 구매 경로의 `HotDealStock` 한 행 차감 경합(`POST /api/orders`). 수천 요청이 같은 잔여 수량을 동시에 줄이는 지점.
- **측정 방식**: 낙관락(@Version) · 원자적 조건부 UPDATE · Redis 원자(+Lua) 3방식. 비관락·Redisson은 이론 예상으로 배제([ADR-0009](../adr/0009-stock-concurrency-design.md) 결정 2).
- **부하 밖**: 토스 결제 승인 구간은 부하 대상이 아니다([ADR-0001](../adr/0001-payment-gateway-toss.md)) — 부하는 주문 생성 경로에 한정, 결제는 테스트 대역.

## 1. 전략 교체 구조

ADR-0009 결정 1의 "경합 지점이 항상 한 행이라, **잠금 방식만 바꿔 끼우고 똑같은 테스트를 다시 돌린다**"를 코드로 실현한다. 차감 연산을 인터페이스로 추상화하고 3구현을 갈아 끼운다.

```
interface HotDealStockDeductor {
  void deduct(Long hotDealId, int quantity);    // 실패 시 DomainException(SOLD_OUT / CONCURRENT_UPDATE_CONFLICT)
  void restore(Long hotDealId, int quantity);   // 만료 복원 — 같은 행 연산이라 전략에 묶음(부하 대상은 아님)
}
```

| 전략 | 차감 동작 | 실패 신호 | 차감 위치 |
|---|---|---|---|
| 낙관락(@Version) | `findByHotDealId` → 엔티티 `deduct()` → 커밋 시 버전 비교 | 버전 충돌 → `CONCURRENT_UPDATE_CONFLICT`(재시도) / 잔여 부족 → `SOLD_OUT` | DB (읽고 씀) |
| 원자적 조건부 UPDATE | `UPDATE … SET remaining = remaining − :qty WHERE hot_deal_id = :id AND remaining >= :qty` 한 문장 | 영향 행 수 0 → `SOLD_OUT`(충돌·재시도 개념 없음) | DB (읽지 않고 씀) |
| Redis 원자(+Lua) | Redis 키(잔여)에 Lua 스크립트로 원자 차감 후 DB 정합 보정 | Lua 반환값 < 0 → `SOLD_OUT` | Redis(원본) → DB(보정) |

- **선택 방식**: 설정 프로퍼티 `stock.deduct.strategy = optimistic | conditional | redis` + `@ConditionalOnProperty`로 빈 하나만 활성. 벤치마크는 프로퍼티만 바꿔 재기동하고 **같은 k6 시나리오를 재실행**한다(다른 조건은 그대로라 방식 차이만 순수 비교).
- **끼움 지점**: `HotDealStockService.deduct/restore`가 주입된 `HotDealStockDeductor`에 위임한다. `create`·조회 등 비경합 연산은 공통(전략 무관).
- **`@Version` 칼럼 유지**: 조건부 UPDATE·Redis 전략에서도 칼럼은 남긴다(스키마 공통). 낙관락 전략만 그 칼럼을 쓴다 — [ADR-0011 결정 4](../adr/0011-product-inventory-reservation.md)의 "HotDealStock version은 벤치마크 대상이라 유지".

## 2. k6 워크로드

- **시나리오**: 선착순 폭주 — 동시 다수 요청이 재고 적은 핫딜 하나에 `POST /api/orders {productId}`. 정확성(오버셀 0)과 성능(성공 처리량)을 함께 본다.
- **부하 모델**: 개방형 도착률(constant-arrival-rate — 응답을 기다리지 않고 정해진 초당 도착 수로 쏨). 동시 사용자 수 고정이 아니라 도착률 고정이라, 처리 속도가 느린 방식도 같은 압력을 받아 비교가 공정하다.
- **부하 매트릭스**: 도착률 × 재고. 예) 동시 압력 100·500·1000 RPS(초당 요청 수) × 재고 10·100. 각 칸을 3방식 모두 측정.
- **데이터 셋업**: 가상 사용자(VU)마다 다른 계정 토큰(계정당 1활성주문 유니크를 충족해야 차감까지 도달). 핫딜·`ProductStock`·`HotDealStock`은 측정 전 시드.
- **단계**: warm-up(캐시·커넥션 풀 예열) → 측정 구간 → 종료 후 **정합 검증**(DB에서 오버셀 0 단언).

## 3. 측정 지표

| 출처 | 지표 | 의미 |
|---|---|---|
| k6(클라이언트) | 성공 처리량(주문 성공 RPS) | 방식별 핵심 비교값 — "초당 몇 건 성공" |
| k6 | 지연 p95·p99(상위 5%·1% 응답 시간) | 꼬리 지연 |
| k6 | 실패율 — 코드별(`SOLD_OUT` / `CONCURRENT_UPDATE_CONFLICT`) | 정직한 실패 vs 경합 밀림 구분 |
| Micrometer→Prometheus(서버) | 낙관락 충돌(`OptimisticLockException`) 횟수 | 낙관락의 재시도 폭증 정량화 |
| 서버 | `hikaricp_connections_active`·`hikaricp_connections_pending` | 커넥션 풀 포화([ADR-0009 결정 3](../adr/0009-stock-concurrency-design.md)) |
| 서버 | DB 차감 UPDATE 지연 | 방식별 쓰기 비용 |
| 부하 후 DB(정확성 단언) | 오버셀 0 — `잔여 + 성공 주문 수량 = 총량`, 거짓 성공 0 | **절대 불변식**([ADR-0006](../adr/0006-correctness-invariants-defense-layers.md)) — 어떤 방식도 이걸 깨면 탈락 |

방식별 예상(검증 대상)은 [ADR-0009 "5방식 예상 분석"](../adr/0009-stock-concurrency-design.md) 표를 따른다 — 낙관락은 성공 처리량 최저, 조건부 UPDATE는 단순·정확하되 단일 행 한계, Redis는 처리량 최고이나 DB 정합 보정 필요.

## 4. 측정 스택 배선

```
k6(부하 생성) ──→ 앱(/actuator/prometheus 노출) ──→ Prometheus(스크랩) ──→ Grafana(통합 대시보드)
   └──────── k6 결과 메트릭도 Prometheus 로 내보냄 ────────────────────────┘
```

- 앱에 Micrometer + `micrometer-registry-prometheus`, actuator 엔드포인트 노출.
- 커스텀 메트릭: 차감 시도/성공 카운터, 낙관락 충돌 카운터(전략 구현에서 기록).
- 한 Grafana 대시보드에서 **부하 곡선(k6)과 서버 지표(풀 포화·충돌)를 같은 시간축에** 겹쳐 본다 — "RPS가 이만큼일 때 풀이 마르고 충돌이 튄다"가 한눈에([ADR-0013](../adr/0013-load-test-tool-k6.md) 선정 이유 ②).

## 5. 측정 절차 → ADR-0010

1. 전략 프로퍼티 설정 → 재기동.
2. 부하 매트릭스(도착률 × 재고)의 각 칸에 k6 실행.
3. 지표 수집(k6 + Prometheus) + 정합 검증(오버셀 0).
4. 3방식 반복.
5. 결과·해석("예상 X, 측정 Y, 차이 이유 Z") → **[ADR-0010](../adr/README.md)에 운영 1방식 확정**.

## 6. 구현 슬라이스 (Phase A 후속)

| 순서 | 작업 | 산출 |
|---|---|---|
| A-1 | 전략 인터페이스 + 낙관락 구현 이전(현재 코드 리팩토링) | `HotDealStockDeductor` + `OptimisticDeductor`, 기존 테스트 GREEN 유지 |
| A-2 | 조건부 UPDATE 구현 | `ConditionalUpdateDeductor` + `@Modifying` 쿼리 |
| A-3 | Redis(+Lua) 구현 | `RedisLuaDeductor` + Lua 스크립트 + DB 정합 보정 |
| A-4 | k6 스크립트 + Prometheus/Grafana 배선 | 워크로드 스크립트, 대시보드 |
| A-5 | 측정 실행 → 결과 문서 | ADR-0010 |

## 7. 전략 구현 테스트 리스트 (누적)

낙관락(A-1)은 기존 동시성 통합 테스트(`CreateOrderConcurrencyIntegrationTest` 등)가 차감 경합을 명세한다. 아래는 신규 전략 구현이 자라며 누적하는 단건 행위 테스트다(전략 간 성능 차이는 테스트가 아니라 A-4·A-5 k6 벤치마크의 몫).

| # | 테스트 | 전략 | 시나리오 | 상태 |
|---|---|---|---|---|
| 1 | `conditionalDeductReducesRemaining` | 조건부 UPDATE | conditional 전략에서 차감 → 잔여 감소 | ✅ |
