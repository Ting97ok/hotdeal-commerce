# ADR-0012. 경계 맥락 지도(context map) — 모듈 경계와 MSA 분리 지점

- 상태: 확정 · 작성: 2026-06-19
- 관련: [ADR-0002 부분 MSA](0002-monolith-first-partial-msa.md) · [ADR-0011 재고 분리](0011-product-inventory-reservation.md) · [service.md 계층 규칙](../../.claude/rules/service.md)

## 컨텍스트

"핫딜을 주문(order)에 합쳐야 하나, 독립 도메인이어야 하나"를 엔티티 관계(핫딜 1 : N 주문)로 판단하려다 층위를 혼동했다. 카디널리티는 **경계 맥락**(bounded context — 한 모델·공통 언어가 일관되게 통하는 범위)을 정하지 않는다. 같은 맥락 안에도 1:N 관계는 흔하다(주문 1 : N 주문라인). 두 층위를 가른다:

- **경계 맥락**(전략 설계 = 미래 MSA 서비스 경계): **변경 이유**로 가른다.
- **집합 단위**(aggregate — 한 맥락 안의 트랜잭션 일관성 묶음): **엔티티 관계**로 가른다.

모놀리식이지만 "언젠가 MSA 전환 시 경계가 무너지면 기술 부채"라는 요구가 있어, **모듈 경계 = 미래 분리 지점(seam)** 으로 보고 설계 의도를 박는다.

## 결정 1 — 경계 맥락 2개 (실제 MSA 분리 지점)

| 경계 맥락 | 포함 | 일관성 | MSA |
|---|---|---|---|
| **구매 코어** | 상품·재고·핫딜·주문·결제(승인까지) | 강한 일관성 — 오버셀 0 은 한 트랜잭션 | 한 서비스로 유지 |
| **결제 후속** | 알림·정산·이력 등 | 최종 일관성 허용 | Kafka + Saga 로 분리 |

근거·전환 범위는 [ADR-0002](0002-monolith-first-partial-msa.md). **핫딜·주문·재고는 모두 구매 코어다** — 패키지 위치를 어떻게 두든 MSA 분리 경계와 무관하다(전환해도 한 서비스에 같이 남는다). 그러므로 "핫딜이 어느 도메인 소속이냐"는 *MSA 부채와 직접 관련이 없다*. 부채는 아래 결정 3(맥락 간 의존)에서 갈린다.

## 결정 2 — 구매 코어 내부 모듈 = 변경 이유로 가른 독립 도메인

한 경계 맥락 안에도 **변경 이유가 다른 모듈은 나눈다**(미래에 더 쪼갤 옵션 = 분리 지점 예약). 합치면 그 지점이 사라져 부채가 된다.

| 모듈(`domain/`) | 책임 | 변경 이유 / 주체 |
|---|---|---|
| `product` | 상품 카탈로그(이름·정가·설명) | 상품 등록·정보 변경 (저빈도) |
| `stock` | 재고 — `ProductStock`(원본: 실물·예약) + `HotDealStock`(핫딜 예약) | **동시성·정합성** (이 프로젝트 핵심 난제) |
| `hotdeal` | 프로모션 오퍼 정의(특가·기간·한정수량) | **선착순 행사 운영** (핵심 차별 영역) |
| `order` | 구매 트랜잭션(누가·무엇을·언제) | 구매 흐름·상태 전이 |
| `payment` | 결제 시도/승인 + 후속 이벤트 발행 지점 | 결제·정산 |

- **`stock` 독립** — 재고는 상품/핫딜 *정보* 가 아니라 *경합하는 수량* 이다([ADR-0011](0011-product-inventory-reservation.md) 결정1: 경합 수량을 정보 행에서 떼어낸다). 그 관심사를 코드 모듈에서도 한곳에 모은다 — 3주차 5방식 동시성 벤치마크·정합 검증식이 한 모듈에 응집한다. **층위 구분**: `ProductStock` 의 데이터 *원본 기준* 은 상품 SKU 이지만(ADR-0011), 코드 *모듈* 은 재고 관심사로 묶는다. "상품에 원본 보관"(데이터 모델)과 "stock 모듈 소속"(코드 구조)은 충돌하지 않는다.
- **`hotdeal` 독립** — 핫딜 선착순이 이 프로젝트의 **핵심 차별 영역(core domain — 도메인 중 가장 중요한 경쟁력 영역)** 이다. order CRUD 에 흡수하면 core domain 이 흐려진다. 핫딜(오퍼 정의)과 주문(구매 트랜잭션)은 변경 이유가 다르고, **주문 0건인 핫딜이 존재하며**(생명주기 독립), 주문은 핫딜을 **참조**할 뿐 포함하지 않는다.
- **외부 근거**(자기참조 탈피 · 2026-06-20 조사) — 선착순 한정수량(seckill/flash sale)을 독립 컨텍스트로 두는 게 표준이다: Apache ServiceComb SecKill 참조 구현은 선착순을 독립 바운디드 컨텍스트(Command/Query 마이크로서비스 분리)로 정의하고, 고트래픽 flash sale 은 전용 재고를 별도 저장소에 격리한다(일반 주문과 트래픽 형태·재고 경합·상태기계가 다름). 단순 가격 할인이 cart/pricing 에 흡수되는 것(Shopify Functions)과 달리, 우리 핫딜은 **한정수량·전용재고(HotDealStock)·선착순**이라 분리가 정당하다.

## 결정 3 — 모듈 간 의존 규칙 (진짜 부채 방지선)

경계가 안 무너지는 핵심은 패키지를 몇 개로 쪼개느냐가 아니라 **의존을 어떻게 거느냐**다.

- **구매 코어 내부**: Facade 경유 동기 호출 허용([service.md](../../.claude/rules/service.md)). 강한 일관성이라 한 트랜잭션으로 묶는다.
- **구매 코어 → 결제 후속**: **이벤트(Kafka)만**. 동기 호출 금지 — 이 한 선이 미래 MSA 분리 지점을 살린다([ADR-0002](0002-monolith-first-partial-msa.md): 모놀리식 단계에서도 이벤트 발행 지점을 식별).
- **참조 방향**: 주문·결제 → 핫딜·재고·상품 (구매가 카탈로그를 참조). **역방향 금지** — 상품·재고는 주문을 알지 못한다.

## 스코프 / 비채택

- 지금은 **모놀리식 모듈 경계**다. 실제 물리 분리는 결제 후속 한 곳([ADR-0002](0002-monolith-first-partial-msa.md)). 모듈을 또렷이 둔 건 미래 옵션 보존이지 당장 분리가 아니다.
- context map 의 무거운 패턴(ACL·Conformist·OHS 등 맥락 간 번역·방어 장치)은 6개 도메인·단일 저장소 규모에 과설계라 미채택 — 경계 2개 + 의존 규칙으로 충분.
- 의존 규칙 자동 강제(ArchUnit)는 미도입 — 코드 리뷰가 안전장치([service.md](../../.claude/rules/service.md)).

## 출처

- [DDD = 논리 경계(무엇을 분리) / MSA = 물리 구현(어떻게 분리)](https://medium.com/codetodeploy/building-a-100k-qps-seckill-system-a-complete-architecture-guide-52c65812c3c1)
- [e커머스 경계 맥락 — Catalog·Inventory·Pricing/Promotion·Order 분리](https://elitex.systems/blog/ecommerce-microservices-guide)
- ["Product"는 맥락마다 다른 모델(Catalog ≠ Inventory)](https://simonatta.medium.com/e-commerce-by-ddd-bf4459272188)
- [Apache ServiceComb SecKill — 선착순을 독립 바운디드 컨텍스트(Command/Query 분리)로](https://servicecomb.apache.org/docs/seckill-development-journey-part-I/)
- [고동시성 Flash Sale 재고 격리(Redis+Lua) — 전용 재고 별도 저장소](https://nileshblog.tech/designing-a-high-concurrency-flash-sale-stock-inventory-reservation-system-with-node-js-redis-lua-and-mongodb/)
