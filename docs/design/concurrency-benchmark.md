# 동시성 벤치마크 설계 — 재고 차감 3방식

> 핫딜 재고 차감(`HotDealStock`)을 3방식으로 **동일 조건 측정**해 운영 1개를 고르는 벤치마크의 구현 설계.
> **결정 정본은 ADR** — 측정 대상·지표는 [ADR-0009](../adr/0009-stock-concurrency-design.md), 부하 도구는 [ADR-0013](../adr/0013-load-test-tool-k6.md), 결과·운영 전략 확정은 [ADR-0010](../adr/0010-concurrency-strategy-selection.md). 본 문서는 그 결정을 **어떻게 구현·측정하는지**를 정의한다.
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
- **`@Version` 칼럼 — 측정 당시 스키마 공통 유지, 확정 후 제거**: 측정 기간엔 전략 무관하게 칼럼을 남겼다([ADR-0011 결정 4](../adr/0011-product-inventory-reservation.md)). [ADR-0010](../adr/0010-concurrency-strategy-selection.md) 확정 후 낙관락 전략·`version` 칼럼은 코드·스키마에서 제거됐다(측정 결과는 ADR-0010 표에 보존, 코드 재현은 git 히스토리).

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

## 7. 다중 인스턴스 측정 (2026-07-24 실측)

앞 측정은 **앱 1인스턴스**다. 배포 전제는 서버 여러 대이므로([기술 가설 3장](hotdeal-purchase-hypothesis.md)), 다중 인스턴스에서만 드러나는 것을 따로 측정했다.

### 구성

```
k6 → nginx(LB, 18080) → 앱 컨테이너 ×1 또는 ×3 → MySQL 1 + Redis 1 (tmpfs)
```

- [docker-compose.multi.yml](../../k6/benchmark/docker-compose.multi.yml) — app1~3 + nginx 라운드로빈. 단일 스택과 격리(`commerce-bench-multi`).
- [run-multi.sh](../../k6/benchmark/run-multi.sh) — 전략 × 인스턴스 수 순회 + 오버셀 검증 + raw 로그 보존(`results/`).
- **공정성**: 인스턴스 1도 nginx 를 경유시켜 **앱 대수만 변수**로 뒀다(LB 홉 유무가 섞이지 않도록).
- 앱 호스트 포트(18081~3)는 부하용이 아니라 인스턴스별 `actuator/prometheus` 관측용이다.

```bash
bash k6/benchmark/run-multi.sh                    # 전체
CONN_VU=1000 VUS=1000 bash k6/benchmark/run-multi.sh   # 한 점만
```

### 결과 ① 오버셀 0 — 인스턴스 수와 무관 (깨끗한 실측)

재고 10 < 인원 1,000 품절 경합, **앱 3인스턴스**:

| 전략 | 성공 | 오버셀 |
|---|:---:|:---:|
| 원자적 조건부 UPDATE | 정확히 **10** | **0** |
| Redis+Lua | 정확히 **10** | **0** |

"차감의 최종 직렬화가 DB(또는 Redis)에 있으니 앱이 몇 대든 같다"는 [nfr 다중 인스턴스 전제](nfr.md)가 **논리적 추론에서 실측으로** 올라섰다. 이 관측은 CPU 경합과 무관해 로컬 환경에서도 신뢰할 수 있다.

### 결과 ② 커넥션 총량 = N × 풀 크기 (깨끗한 실측)

앱 3인스턴스·VU 1,000 부하 중 2초 간격 스냅샷:

| 지표 | 값 |
|---|---|
| 인스턴스별 `hikaricp_connections_active` 합산 | **30** (=3 × 풀 10) |
| DB `Threads_connected` | **31** (측정 세션 1 포함) |
| DB `Threads_running` | **2** |

산정식이 그대로 맞았다. 동시에 **커넥션 31개 중 실제 실행은 2개뿐** — 나머지는 잠금 대기로 묶여 있었다([ADR-0009 결정 3](../adr/0009-stock-concurrency-design.md)이 경고한 "대기가 커넥션을 점유해 공유 풀을 마르게 한다"의 실물).

### 결과 ③ 처리량 — 스케일아웃해도 안 늘고 오히려 악화

재고 = VU × 2(전원 차감), VU 1,000:

| 전략 | i1 p95 (동률·순위 요동) | i3 p95 | 행 잠금 대기(누적) |
|---|---|---|---|
| 원자적 조건부 UPDATE | 1.1~1.9s | **4.4s** | 7.5s → **71.6s** |
| Redis+Lua | 1.4~1.5s | **2.6s** | **0** (행 미접근) |

- **i1 은 동률**이다 — 재실행 시 순위가 뒤집혀(conditional 1.14s ↔ 1.93s) 노이즈 범위로 본다.
- **i3 에서 격차가 벌어진다** — 앱을 늘려 단일 행 압력을 3배로 주니 DB 에 매인 conditional 만 급증했다.
- **행 잠금 대기가 i1→i3 에서 약 10배**(7.5s→71.6s)로 늘었다. 부하 전후 `Innodb_row_lock_waits`·`Innodb_row_lock_time` 델타로 수집한다.

### 한계 — 무엇을 못 밝혔나

- **i3 악화의 원인을 분리하지 못했다.** "단일 행 한 줄 병목"인지 "로컬 1머신에서 앱 3개가 CPU 를 다툰 것"인지 가를 수단이 없다. Redis 의 행 잠금 0 은 코드상 행을 안 건드리니 **자명**해서 대조군으로 약하다.
- **tmpfs 환경**이라 실제 운영의 커밋 저장(디스크) 비용이 빠져 있다 — 여기서 본 병목은 잠금·CPU 쪽이지 디스크가 아니다.
- **단일 측정**이라 절대 수치에 노이즈가 있다. 신뢰하는 건 방향(i3 격차·행 잠금 폭증)이지 값이 아니다.
- **스케줄러 중복은 미측정** — 측정 오염을 막으려 만료·해소 스케줄러를 끄고 돌린다. 그 항목은 여전히 설계 논리([payment system 설계](../payment/api-design-system.md) 처리권 선점).

### 후속

1. **물리 분리 측정** — 부하기·앱·DB 를 다른 머신에 두어 i3 격차의 원인을 규명한다. 이게 남은 것 중 가장 크다.
2. **인원 곡선 스윕** — conditional 이 SLA(폭주 p95 ≤ 2s)를 실제로 깨는 지점을 좁힌다.
3. **세 번째 전략 측정** — MySQL `SKIP LOCKED` + 재고 단위별 행([Shopify 사례](https://shopify.engineering/scaling-inventory-reservations)). 조건부의 안전성(DB 단일 진실·주문과 한 트랜잭션)을 지키면서 한 줄 병목만 없애는 접근이라, Redis 의 이중 관리를 피할 수 있는지가 관심사다. **미측정**이며 채택하려면 보충 정책·격리 수준(갭 락 회피)·복원 경로를 함께 설계해야 한다.

## 8. 전략 구현 테스트 리스트 (누적)

낙관락(A-1)은 기존 동시성 통합 테스트(`CreateOrderConcurrencyIntegrationTest` 등)가 차감 경합을 명세한다. 아래는 신규 전략 구현이 자라며 누적하는 단건 행위 테스트다(전략 간 성능 차이는 테스트가 아니라 A-4·A-5 k6 벤치마크의 몫).

| # | 테스트 | 전략 | 시나리오 | 상태 |
|---|---|---|---|---|
| 1 | `conditionalDeductReducesRemaining` | 조건부 UPDATE | conditional 전략에서 차감 → 잔여 감소 | ✅ |
| 2 | `conditionalDeductRejectsWhenInsufficient` | 조건부 UPDATE | 잔여 부족 시 영향 행 0 → SOLD_OUT (잔여 불변) | ✅ |
| 3 | `conditionalRestoreIncreasesRemaining` | 조건부 UPDATE | 복원 → 잔여 증가 | ✅ |
| 4 | `conditionalDeductNoOversellUnderConcurrency` | 조건부 UPDATE | 동시 100·재고 10 → 정확히 10 성공, 오버셀 0 | ✅ |
| 5 | `redisDeductReducesRemaining` | Redis(+Lua) | redis 전략 차감 → Redis 잔여 감소 | ✅ |
| 6 | `redisDeductRejectsWhenInsufficient` | Redis(+Lua) | 잔여 부족 시 Lua 0/-1 반환 → SOLD_OUT | ✅ |
| 7 | `redisRestoreIncreasesRemaining` | Redis(+Lua) | 복원 → Redis 잔여 증가 | ✅ |
| 8 | `redisDeductNoOversellUnderConcurrency` | Redis(+Lua) | 동시 100·재고 10 → 정확히 10 성공, 오버셀 0 | ✅ |
