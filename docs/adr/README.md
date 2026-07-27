# 의사결정 기록 (ADR) — 인덱스

> ADR(Architecture Decision Record) = 설계 갈림길에서 **무엇을, 왜** 선택했는지 남기는 문서.
> 문서 체계: [가설 PRD](../design/hotdeal-prd.md)(사업 언어의 요구·정책) → [기술 가설](../design/hotdeal-purchase-hypothesis.md)·[ERD](../design/erd.md)(결정된 규칙) → **ADR(결정의 고민 전부)**.
> **살아 있는 문서** — 결정이 바뀌면 새 번호로 대체하지 않고 **그 문서를 직접 최신화**한다. 변경 이력은 git 이 관리하므로 빈 번호(결번)를 두지 않는다.
>
> **RFC ↔ ADR 역할** — 본 저장소는 RFC(결정 *전* 토론용 긴 캔버스)를 별도 파일로 두지 않는다. ADR 하나가 두 역할을 겸한다: 머리의 **결정 요약**(무엇을 / 왜 / 버린 대안 / Non-Goal(범위 밖) / 트레이드오프 / 가역성 Type — 1페이지, "6개월 뒤 30초 파악")과, 그 아래 **컨텍스트·대안·설계노트**(왜 그 결정인지 고민 전부 보존 — RFC 역할). 요약만 읽으면 결정 결과, 본문까지 읽으면 결정 과정. **"6개월 뒤 새 멤버가 'X 왜 썼어요?' 물을 결정은 전부 ADR"** 이 이 폴더의 수록 기준이다. (요약 헤더 포맷은 0002·0004·0005·0008·0009·0010·0011·0012·0013 에 적용 — 짧고 자명한 0001·0003·0006·0007 은 본문이 곧 1페이지라 생략.)

## ADR 문서

| 번호 | 제목 | 무엇을 왜 택했나 (한 줄) | 상태 |
|---|---|---|---|
| [0001](0001-payment-gateway-toss.md) | 결제 PG | 사업자 없이 테스트 무료 + 예외·멱등 문서 품질로 **토스** 선정 | 확정 |
| [0002](0002-monolith-first-partial-msa.md) | 모놀리식 + 부분 MSA | 전면 MSA 대신 **결제 도메인 한 경계만** 별도 서비스로 전환(2026-07-03 상향 — 후속 처리→결제 도메인, 새 레포=전환본·현 레포=스냅샷) — 정당성 없는 분리는 감점 | 확정 (본편 완료, 전환 착수) |
| [0003](0003-no-db-fk-constraints.md) | DB FK 제약 미사용 | FK 안 걸기 — 폭주 쓰기의 부모 행 잠금 제거·MSA 대비, 무결성은 앱+테스트가 책임 | 확정 |
| [0004](0004-stock-reservation-lifecycle.md) | 재고 선점·복원 생명주기 | 재고는 **주문 시 선점**(결제 후 차감은 선착순 의미 깨져 기각), 복원은 정확히 한 번, 성공 결제는 **재고 재확보로 되살림 우선 — 재확보 불가 충돌만 취소+환불+보상 통보**(무통보 환불 ❌, 2026-07-03 실무형 갱신) | 확정 |
| [0005](0005-one-per-user-active-unique.md) | 1인 구매 제한 | 세 겹 — 계정당 1활성주문(활성 유니크) + 주문당 `maxPerOrder` + 총량 `maxPerAccount`(③결제단계). 슬라이스별 구현, 정책은 문서로 전부 정의 | 확정 |
| [0006](0006-correctness-invariants-defense-layers.md) | 정확성 불변식·방어 계층 | **오버셀 0 + 거짓 성공 0 = 절대, 덜 팔림은 허용**. 방어는 중복이 아니라 층별 **분업** | 확정 |
| [0007](0007-hotdeal-state-operations.md) | 핫딜 상태·운영 정책 | 진행/매진은 상태값 대신 **기간+재고로 판단**, 진행 중 수정 대신 **새 회차**, 기간 겹침 등록 금지, **핫딜=단일 상품 단위**(이벤트 범위 밖·확장 경로) | 확정 |
| [0008](0008-payment-model-pg-boundary.md) | 결제 모델·PG 경계 | 결제는 **시도(paymentKey)마다 1행**, 토스 의존은 **어댑터 한 곳**에 격리 | 확정 |
| [0009](0009-stock-concurrency-design.md) | 재고 동시성 설계 | 재고를 **별도 행**으로 떼어 5방식 비교, 비관락은 **커넥션 풀 보호**로 즉시 실패(NOWAIT) | 확정 |
| [0010](0010-concurrency-strategy-selection.md) | 동시성 운영 전략 선정 | 격리 측정: 고경합서도 조건부 ≈ Redis(호스트 측정의 Redis 역전은 SQL 로깅 오염), 셋 다 오버셀 0 — **조건부 채택** | 확정 |
| [0011](0011-product-inventory-reservation.md) | 상품 재고·핫딜 예약 | 재고는 **상품에 원본 보관**(`ProductStock` 실물·예약·가용), 핫딜은 거기서 예약. 경합 격리(재고 전용 테이블)를 상품 레벨로 일반화 | 확정 |
| [0012](0012-context-map-module-boundaries.md) | 경계 맥락 지도 | 경계 맥락은 **변경 이유**로 가른다(카디널리티 ❌). 구매 코어 1 + 결제 후속 1, 핫딜·재고는 **독립 모듈**이되 다 구매 코어 — 패키지 위치는 MSA 분리와 무관, 부채 방지선은 **모듈 간 의존 규칙**(코어 내부 동기 / 후속은 이벤트) | 확정 |
| [0013](0013-load-test-tool-k6.md) | 부하 테스트 도구 | 락 3방식 **동일 조건 반복 측정** + 서버 메트릭 통합 시각화로 **k6** 선정 | 확정 |

## 파일 없는 한 줄 결정

| 결정 | 내용 | 근거 위치 |
|---|---|---|
| 구매 흐름·상품 재고 | Product 가 재고 원본 보관(`ProductStock` 실물·예약·가용) → 핫딜은 거기서 예약. 구매 API 는 **상품 주소**(`POST /api/orders {productId}` — 서버가 활성 핫딜 해소), 주문 모델은 **상품 참조 + 적용 핫딜 기록**(표준). 슬라이스 1 확정(2026-06-20 상품 주소로 전환) | [ADR-0011](0011-product-inventory-reservation.md) |
| **주문 식별자** | 공개 카탈로그(상품·핫딜)는 순번 id 노출 / 민감 리소스(주문)는 불투명 `order_no`(UUID, UNIQUE) — 권한 검증 실수의 안전망 + 주문량(사업 지표) 은닉 + 토스 주문번호 겸용. PK 는 순번 유지(UUID 를 PK 로 쓰면 InnoDB 삽입 성능 저하). BaseEntity 공통 UUID 는 5개 테이블 낭비라 미채택 | [erd 4·6](../design/erd.md) |
| **금액 타입** | 전 금액 칼럼 `DECIMAL(12,0)` / JPA `BigDecimal` — 금액은 정확 십진수가 원칙(부동소수점 금지), BIGINT 대비 소수 확장(할인율·수수료)이 자릿수 변경으로 끝나고 스키마에 "돈" 의미가 드러남. (KRW 전용은 BIGINT 도 정당 — 의미·확장성으로 DECIMAL 선택) | [erd 6](../design/erd.md) |
| 범위 밖 묶음 | 대기열·어뷰징 방어 / 승인 후 환불 / 주문 내역 조회·이어서 결제(만료 후 재구매로 갈음) / 다중 PG 일반화 / **멀티상품 이벤트**(핫딜=단일 상품 — 확장 경로 [0007](0007-hotdeal-state-operations.md) 결정6) | 가설 2 |
| 슬라이스 순서 | 0 등록 → 1 구매 → 2 만료 복원 → 3 결제 승인 — 어느 시점에 멈춰도 시스템 자기 완결 | 가설 9 |
| 배포 전제 | 서버 여러 대(LB 뒤, MySQL·Redis 공유) — JVM 잠금 무효 | 가설 3 |
| 인증 | 무상태 JWT(RTR·tokenVersion) — 구현 완료 | [auth.md](../design/auth.md) 1·4 |

## 우리가 피한 설계 안티패턴 (리뷰 6종)

시니어 설계 리뷰에서 가장 자주 잡아내는 6가지를, 이 프로젝트가 어디서 어떻게 피했는지 각 결정으로 역추적한다 — 리뷰에서 "이거 안티패턴 아닌가요?"에 근거로 답하려면 6개가 이름을 갖고 있어야 한다.

| 안티패턴 | 증상 | 이 프로젝트가 피한 방식 | 근거 |
|---|---|---|---|
| **Resume-driven design** | "이력서에 쓰려고" 도구 도입 — 문제가 도구를 부르지 않고 도구가 문제를 찾음 | "적은 API + 깊이" 정체성 · 전면 MSA 미채택("정당성 없는 분리는 감점") · 5방식 전부 구현 대신 3측정 + 2이론배제("취사선택이 판단력") · k6도 이력서용 아닌 측정 목적(재현성·서버 메트릭)으로 근거 | [0002](0002-monolith-first-partial-msa.md) · [0009](0009-stock-concurrency-design.md) · [0013](0013-load-test-tool-k6.md) |
| **Premature abstraction** | "나중에 바꿀 수도" 인터페이스 남발 — 안 바뀌고 디버깅만 어려워짐 | 어댑터는 인터페이스 1개(비용 ~0)만, "교체 보장 아닌 교체 지점 격리" · 다중 PG는 둘째 PG 필요 시 · 안전재고·다중 창고 스코프 밖 · 가용은 계산(저장 안 함) | [0008](0008-payment-model-pg-boundary.md) · [0011](0011-product-inventory-reservation.md) |
| **Distributed monolith** | MSA로 쪼갰는데 동기로 강결합 — 모놀리스 단점 다, MSA 장점 0 | 구매 코어 → 결제 후속은 **이벤트만**(동기 금지, "이 한 선이 미래 분리 지점을 살린다") · 재고는 분리 안 함(최고 결합, 강한 일관성 유지) · 참조 방향 단방향 | [0012](0012-context-map-module-boundaries.md) 결정3 · [0002](0002-monolith-first-partial-msa.md) |
| **Shared mutable database** | 서비스는 쪼개졌는데 DB 하나 — 한쪽 스키마 변경이 다른 쪽 깸 | 결제 분리 시 별도 DB · FK 미사용("경계 넘는 FK 어차피 불가 — 미리 같은 규율") · 참조 방향 단방향(카탈로그는 주문을 모름) | [0002](0002-monolith-first-partial-msa.md) · [0003](0003-no-db-fk-constraints.md) · [0012](0012-context-map-module-boundaries.md) |
| **Silent failure** | try/catch로 다 삼킴 — "에러가 안 보여요"가 가장 위험, 실패가 침묵하면 학습이 멈춤 | 예상 못한 예외는 **삼키지 않고 전파(500)**("catch-all은 버그를 미확정으로 위장하는 안티패턴") · 미확정(IN_DOUBT)은 삼키지 않고 보존→해소가 확정 · 정직한 실패("다시 시도", 서버 몰래 재시도 금지) | [payment B1](../payment/api-design.md) · [0006](0006-correctness-invariants-defense-layers.md) |
| **No rollback path** | "잘 될 겁니다" 시나리오만, "안 되면 되돌리는 법" 없음 | TX 분리의 자동 롤백 상실을 **명시적 보상**(markPending·restoreSale)으로 대체 · 되돌리는 법을 결정마다 문서화(아래 전환 경로 색인) | [payment B1](../payment/api-design.md) · 아래 표 |

## 결정을 되돌리는 법 — 전환 경로 색인

"No rollback path"를 피하려면 결정마다 **틀렸다는 신호 + 되돌리는 절차**가 있어야 한다(RFC 의 Risks·Rollout 슬롯). 이 저장소는 이미 배포된 완성본이라 "단계적 rollout" 대신 슬라이스로 자랐고, 여기 모으는 건 각 결정의 **되돌리기·전환 경로**다. Type2(되돌리기 쉬움)는 절차가 가볍고, Type1(어려움)은 애초에 신중히 결정한 이유가 여기 드러난다.

| 결정 | Type | 틀렸다는 신호 | 되돌리는·전환 절차 |
|---|:---:|---|---|
| 재고 조건부 UPDATE ([0010](0010-concurrency-strategy-selection.md)) | Type2 | 폭주 p95 > 2s(SLA 위반) — 단일 행 **한 줄 병목** 초과 | 프로퍼티 `stock.deduct.strategy=redis` 교체 + Redis→DB 정합 구현 / 또는 재고를 여러 행으로 분산 |
| 재고 한 행 ([0011](0011-product-inventory-reservation.md)) | Type1 | 단일 행 쓰기 **한 줄 병목** 도달(~500 QPS 는 [0009](0009-stock-concurrency-design.md) 예상 표의 **예상치** — 미실측) | 재고를 여러 행으로 분산 — 버킷 분산(Alibaba) · 단위별 1행 + MySQL `SKIP LOCKED`([Shopify](https://shopify.engineering/scaling-inventory-reservations)). 본 프로젝트 **미측정** |
| FK 미사용 ([0003](0003-no-db-fk-constraints.md)) | Type2 | 참조 무결성을 DB 강제로 필요 | `ALTER TABLE ADD CONSTRAINT`(기존 데이터 정합 검증 후) |
| 활성 유니크 ([0005](0005-one-per-user-active-unique.md)) | Type1(비대칭) | 총량 상한(maxPerAccount) 필요 | `is_active` PENDING만으로 재정의 + 잠금 카운트 / PostgreSQL 이행 시 부분 유니크로 단순화. 제거는 `DROP INDEX`(온라인 DDL) |
| 증량 금지 ([0007](0007-hotdeal-state-operations.md)) | Type2 | 진행 중 딜 증량 실무 필요 | `totalQuantity += N`·`remaining += N` 한 트랜잭션 API 추가("등록 후 불변" 해제) |
| 부분 MSA ([0002](0002-monolith-first-partial-msa.md)) | Type1 | (분리 자체가 순방향 결정) | 되돌리기 비쌈(DB·이벤트 계약) — **그래서** 본편 완수 후 착수, 정당성 있는 경계만 |
| PG 어댑터 ([0008](0008-payment-model-pg-boundary.md)) | Type1 | 둘째 PG 실제 필요 | 어댑터 1곳에 PG별 구현 추가 + 공통 에러 정규화·라우팅(교체 지점이 이미 격리됨) |

> **시니어 설계 프레임과의 대응**: 이 프로젝트의 몇 결정은 공개된 시니어 설계 사례와 같은 프레임을 탄다 — 결제 TX 분리([0008](0008-payment-model-pg-boundary.md))는 **"사용자가 화면에서 기다리는가?"** 로 동기/비동기를 가르는 판단(재고 확인·주문 ID 는 동기, 결제 호출·알림·영수증은 화면 밖)이고, 부분 MSA([0002](0002-monolith-first-partial-msa.md))는 **"팀이 서로 막나 / 한 모듈만 폭주하나 / 도메인 경계 정말 있나"** 3신호로 분리를 판단한 결과(기본값은 모듈러 모놀리스, 신호 켜진 결제 경계만 분리)다. 다음 작업인 고트래픽 상품 조회 최적화는 **검색·조회 인덱스 결정**을 새 ADR로 남기는 지점이 된다.
