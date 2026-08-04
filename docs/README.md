# 커머스 핫딜 — 백엔드 설계

> 트래픽이 한순간에 몰리는 **핫딜 선착순 구매**를, 재고보다 많이 팔리는 일(초과 판매) 0건으로 막고 토스 결제까지 정합성 있게 잇는 백엔드 프로젝트. 넓은 기능 대신 **이 한 흐름을 끝까지 깊게** 판다(핵심 흐름 + 다역량 각각 깊게).
> 상태: 인증(JWT·RTR)·회원 → 핫딜 등록·주문·만료·결제(슬라이스 0~4) → 동시성 벤치마크·운영 전략 확정(Phase A, [재고 동시성 ADR](adr/concurrency.md)) → 토스 실연동(Phase B1) → IN_DOUBT 해소 스케줄러(Phase B2) **완료**. 다음: 결제 후속 부분 MSA 분리 → 대용량 조회·캐싱.

## 이 프로젝트가 내린 핵심 결정 (한 줄 + 왜)

| 결정 | 왜 이렇게 했나 | 상세 |
|---|---|---|
| **초과 판매 0 + 거짓 성공 0** (덜 팔림은 허용) | "거의 맞음"은 실패 — 샀는데 못 받는 게 최악. 폭주 실패는 정직하게 "다시 시도" | [주문 ADR 6절](adr/order.md) |
| **주문 시 재고 선점** | 선착순 = "먼저 잡은 사람" — 결제 후 차감은 그 의미가 깨진다 | [주문 ADR 1절](adr/order.md) |
| **1인 구매 제한 (계정당 1주문 = DB 활성 유니크)** | 동시 클릭은 DB 활성 유니크로만 직렬화 가능. 주문당 `maxPerOrder`·총량은 결제단계 | [주문 ADR 4절](adr/order.md) |
| **재고 동시성 3방식 실측 + 2방식 이론 배제** | 운영은 1개, 나머지는 그 선택의 근거 — 무엇을 재고 무엇을 이론으로 접을지 고르는 것 자체가 판단 | [재고 동시성 ADR](adr/concurrency.md) |
| **운영 재고 차감 = 원자적 조건부 UPDATE** | 격리 벤치마크 실측 — 고경합서 Redis 와 동률·낙관적 락 성공 16% 탈락. 호스트 측정의 Redis 우위는 SQL 로깅 오염이었다 | [재고 동시성 ADR](adr/concurrency.md) |
| **결제는 어댑터 뒤로 격리** | 토스 의존이 비즈니스 로직에 새지 않게 — 교체 지점을 한 곳에 모음 | [결제 ADR 3절](adr/payment.md) |
| **DB FK 제약 미사용** | FK 가 주는 것은 실수 방지 하나인데 그 대가가 데이터를 정리 못 하는 것 — 코드는 가드로, 사람은 권한으로 갚는다 | [참조 무결성 ADR](adr/integrity.md) |

전체 결정 지도는 [ADR 인덱스](adr/README.md).

## 진행 상태

- ✅ 인증(JWT·RTR·tokenVersion), 회원(User)
- ✅ 핫딜 설계 확정 — [가설 PRD](design/hotdeal-prd.md) · [기술 가설](design/hotdeal-purchase-hypothesis.md) · [ERD](design/erd.md) · [ADR](adr/README.md)
- ✅ 엔티티+마이그레이션 — 상품·핫딜·재고·주문·결제 (V2~V6, FK 제약 미사용·활성 유니크·CHECK 5종)
- ✅ 슬라이스 0~4 — 핫딜 등록 · 주문(선점·활성 유니크) · 미결제 만료 스케줄러 · 결제 승인 (vertical TDD)
- ✅ Phase A — 동시성 벤치마크(낙관적 락·조건부·Redis) → 운영 전략 조건부 UPDATE 확정 ([재고 동시성 ADR](adr/concurrency.md))
- ✅ Phase B1 — 토스 실연동: TX 경계 분리 · sealed 4결과 분류 · IN_DOUBT 보존
- ✅ Phase B2 — IN_DOUBT 해소 스케줄러 (토스 재조회 → DONE 확정 / 실패 확정 + 재고 방출)
- 🚧 다음: 결제 후속 처리 부분 MSA 분리 → 대용량 조회·Redis 캐싱

## 어디부터 읽나

**문서 체계**: [가설 PRD](design/hotdeal-prd.md)(무엇을 약속하나) → [기술 가설](design/hotdeal-purchase-hypothesis.md)(어떤 규칙으로 지키나) → [ERD](design/erd.md)(데이터 모양) → [ADR](adr/README.md)(왜 그렇게 정했나). 근거 사례는 [리서치](design/research-flash-sale.md).

**5분 코스 (전체 그림)**: 위 "핵심 결정" 표 → [가설 PRD](design/hotdeal-prd.md) 2장(검수 지표)·5장(요구 18건) → [기술 가설](design/hotdeal-purchase-hypothesis.md) 4장(불변식)·9장(슬라이스).

**전체 코스 (순서대로)**:

| 순서 | 문서 | 읽으면 알게 되는 것 |
|---|---|---|
| 1 | [프로젝트 개요](project-plan.md) | 증명하려는 역량·정체성·MoSCoW·기술 결정 요약·진행 순서 |
| 2 | [가설 PRD](design/hotdeal-prd.md) | 이벤트의 약속·전제·사용자 시나리오·요구 18건·오픈 이슈 |
| 3 | [기술 가설](design/hotdeal-purchase-hypothesis.md) | 불변식 5개·구매 흐름·거부 케이스·동시성 규율·슬라이스 0~3 |
| 4 | [ERD](design/erd.md) | 6개 엔티티·불변식의 스키마 반영·물리 DDL 결정 |
| 5 | [ADR](adr/README.md) → 관심 결정 | 각 결정의 대안·트레이드오프. 추천: [주문 — 선점·복원·불변식](adr/order.md) · [재고 동시성](adr/concurrency.md) · [결제 — 트랜잭션 경계와 미확정](adr/payment.md) |
| 6 | [리서치](design/research-flash-sale.md) | 실무 주장의 출처(궁금할 때 진입) — 신뢰 등급 구분 표기 |

참고: 인증 설계는 [auth.md](design/auth.md) (핫딜과 독립적으로 완결된 선행 작업).

## 읽을 때 약속

- **결정 파악(30초)**: ADR 은 머리 **결론**만 읽으면 무엇을 왜 골랐는지가 잡히고, 본문은 그 근거다. 색인은 [adr/README](adr/README.md).
- **용어**: 각 문서 첫 등장에 한 줄 풀이 병기.
- **신뢰 등급**: 리서치 1~7(적대 검증 통과) > 10(공식·1차 보강) > 9(1차 인용, 미검증) — 인용 시 등급 구분 표기.
- **미결정**: "모른다"가 아니라 "언제 정한다"로 관리 — [PRD 7장 오픈 이슈](design/hotdeal-prd.md), [가설 11장 보류](design/hotdeal-purchase-hypothesis.md).
- **링크 — 타 문서 특정 섹션 참조는 텍스트에 섹션 번호, 링크는 파일 레벨**: 다른 문서의 *특정 결정·장*을 가리킬 땐 섹션 번호를 텍스트에 적고 링크는 파일 레벨로 건다(`[핫딜 ADR 4절](adr/hotdeal.md)`처럼 `#앵커` 없이). `<a id>`·자동 슬러그 앵커는 IntelliJ가 지원하지 않아 쓰지 않는다(파일 레벨 링크는 IntelliJ·GitHub 둘 다 작동).
