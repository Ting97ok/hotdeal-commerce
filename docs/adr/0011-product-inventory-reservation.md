# ADR-0011. 상품 재고 원본 분리(실물·예약·가용) · 핫딜 예약 · 경합 격리 일반화

- 상태: 확정 · 작성: 2026-06-17 · 갱신: 2026-06-20 (결정 4 ProductStock 원자적 조건부 UPDATE 전환 · 구매 상품 주소 전환 · 재고 표시 정책 신설)
- 관련 규칙: [ADR-0009 재고 동시성](0009-stock-concurrency-design.md) · [ADR-0004 예약 생명주기](0004-stock-reservation-lifecycle.md) · [erd](../design/erd.md) · 실측 출처: 본문 하단

## 컨텍스트

현재 모델은 `Product`(이름·정가·설명)에 재고가 없고, 재고가 핫딜 전용 `Stock` 1:1 행에만 있다. 세 가지 갭이 있다.

1. **"100개의 출처가 없다"** — 핫딜 등록 시 `totalQuantity`가 허공에서 생긴다. 실재 상품 재고에서 떼어 온 것이 아니다.
2. **인기 상품 일반 구매 경합 모델 부재** — 핫딜이 아니어도 인기 상품은 재고 차감 경합이 있는데 표현할 자리가 없다.
3. **재고를 상품 행의 칼럼으로 두는 함정** — `Product`에 재고 칼럼을 붙이면 [ADR-0009](0009-stock-concurrency-design.md)가 재고를 떼어낸 그 행 경합·낙관락 가짜 충돌(무관한 수정이 같은 행에 살아 진행 중 구매가 다 실패)을 상품 행에서 재현한다. **DB의 한 행에 대한 쓰기는 줄 서서 처리돼 처리량 상한이 있다 — 표준 MySQL 단일 행 약 500 QPS〔Queries Per Second, 초당 처리 요청 수〕(Alibaba 실측).** 인기 상품이면 이 한계에 그대로 부딪힌다.

실측은 일관된다 — **재고는 상품/SKU〔Stock Keeping Unit, 재고를 세는 최소 품목 단위〕 레벨의 원본(기준 데이터)을 상품 정보와 분리된 별도 테이블에 두고**(실물·예약·가용으로 관리), 프로모션·플래시세일은 그 재고에서 예약/할당한다. 전용 별도 재고를 핫딜이 소유하지 않는다.

## 결정 1 — 경합 격리 원칙 (ADR-0009 일반화)

> **경합하는 수량은 상품 정보 행에 섞지 말고, 재고만 담은 칼럼 적은 별도 테이블에 둔다. 한 행의 쓰기 한계(~500 QPS)를 부하 테스트에서 넘기면 그 재고를 여러 버킷으로 쪼개 여러 행에 분산한다.** 핫딜 레벨에만이 아니라 상품 레벨에도 적용한다.

- 상품 재고는 `Product`의 칼럼이 아니라 별도 테이블(`ProductStock`)에 둔다.
- 핫딜 레벨 `Stock` → `HotDealStock` 리네임(상품 `ProductStock`과 균일·구분). 테이블 `stock` → `hot_deal_stock`.

## 결정 2 — 상품 재고 = 원본, 실물·예약·가용 (`ProductStock`)

상품별 별도 테이블의 한 행(칼럼 적음): `product_id`(UNIQUE 논리 참조) · `on_hand_quantity`(실물 — 창고에 실제 있는 수) · `reserved_quantity`(예약 — 핫딜에 떼어 둔 수). **별도 버전 칼럼은 두지 않는다** — 예약/복원의 동시 차감 충돌은 낙관락(@Version)이 아니라 **원자적 조건부 UPDATE**로 막는다(결정 4).

- **가용 = 실물 − 예약.** 가용은 **별도 칼럼으로 저장하지 않고 필요할 때 계산**한다(저장하면 실물·예약 바뀔 때마다 같이 고쳐야 하고 어긋나면 버그 — 계산하면 항상 정확). 영어로 이 "팔 수 있는 양"을 ATP〔Available-to-Promise, 약속 가능 재고〕라 부른다.
- 한 상품의 실물이 여러 회차에 나눠 쓰이므로(PRD "추가 물량은 새 회차"), 회차 간 초과 배정을 막으려면 예약을 추적해야 한다. 재고를 한 숫자로만 두면 "다른 회차에 얼마 떼였는지"를 몰라 초과 배정이 난다.
- 단순화(스코프 경계): 안전재고·다중 창고·입고예정은 스코프 밖(결정 4).

## 결정 3 — 핫딜은 상품 재고에서 예약 (재고 변화)

한 상품의 실물 재고를 여러 회차가 나눠 쓰므로, 회차마다 필요한 양을 상품 재고에서 *예약*으로 떼어 두고, 핫딜은 그 예약분 안에서만 판다.

| 시점 | 재고 변화 | 슬라이스 |
|---|---|:---:|
| 핫딜 등록 | 상품 가용(실물−예약)이 핫딜 수량 이상인지 확인 → 그만큼 **예약을 늘린다**. 부족하면 등록 거부(`INSUFFICIENT_PRODUCT_STOCK`, 409) | 0 |
| 핫딜 취소/종료 | 안 팔리고 남은 만큼 **예약을 줄인다**(다시 가용으로) | 0/2 |
| 손님 구매(주문 생성, 결제 전) | **핫딜의 남은 수량만 1 줄인다.** 상품 재고는 안 건드린다(등록 때 이미 예약했으므로) | 1 |
| 주문 만료/취소(미결제) | **핫딜의 남은 수량을 1 늘린다**(다시 살 수 있게) | 2 |
| 결제 확정 | 그 1개가 실제로 팔린 것이므로 **상품 실물을 1, 예약을 1 줄인다** | 3 |

> 슬라이스 0 범위는 위 표의 **등록·취소 두 줄뿐**. 나머지는 해당 슬라이스(구매/만료/결제)에서 자란다. 결제 확정은 **실물·예약을 함께 1씩 줄인다**(가용=실물−예약 불변, 판 1개가 실제로 빠짐) — 판매=출고를 한 단계로 합치며 출고/배송 분리는 스코프 밖. 예약만 줄이면 가용이 거꾸로 늘어 모델이 깨진다.

## 결정 4 — ProductStock 동시성 = 원자적 조건부 UPDATE (낙관락 폐기) · 구매 경로 보존 · 스케일 경계

- **구매**(슬라이스 1·요청이 폭주하는 핵심 경로)는 여전히 **`HotDealStock` 한 행만** 차감한다. `ProductStock`은 등록/취소(관리자·저경합)와 **결제 확정**(사용자 경로지만 당첨자만·결제 제한시간 창에 분산되는 저빈도)에서만 건드린다 → [ADR-0009](0009-stock-concurrency-design.md)의 "경합 한 점 집중"·벤치마크 순수성 유지.
- **ProductStock 의 동시 차감은 원자적 조건부 UPDATE 한 문장**으로 막는다(낙관락 폐기):

  ```sql
  UPDATE product_stock
     SET reserved_quantity = reserved_quantity + :qty
   WHERE product_id = :productId
     AND on_hand_quantity - reserved_quantity >= :qty   -- 가용 ≥ 요청
  ```

  판단은 **영향 행 수**로 한다 — 1이면 예약 성공, 0이면 행 존재 여부로 가용 부족(`INSUFFICIENT_PRODUCT_STOCK`, 409)과 행 없음(`STOCK_NOT_FOUND`, 404)을 가른다. 확인-결정-쓰기(TOCTOU — 두 요청이 같은 값을 읽고 둘 다 통과)가 DB 한 문장에 원자화돼 WHERE 가 가용을 보장하므로 오버셀이 구조적으로 불가능하다.
- **왜 낙관락이 아닌가**: ProductStock 은 위처럼 저경합이라 충돌이 드물다. 낙관락(@Version)은 충돌 시 재시도가 필요한데, 재시도는 트랜잭션을 다시 잡아야 해(같은 트랜잭션 안에서 재시도하면 PC〔영속성 컨텍스트, JPA 가 객체를 추적하는 메모리 공간〕가 오염된 채 남는다) Facade 한 트랜잭션 흐름이 복잡해진다. 원자적 UPDATE 는 충돌·재시도 개념 자체가 없어 저경합에서 가장 단순하고 정확하다. 이 방식은 [ADR-0009](0009-stock-concurrency-design.md)의 **5번째 벤치마크 방식**으로도 합류한다.
- **HotDealStock 의 `version` 은 유지(벤치마크 특수 선택)**: `product_stock` 의 version 제거와 달리 `hot_deal_stock` 은 version 칼럼을 남긴다. HotDealStock 은 운영 방식을 고르려는 **동시성 벤치마크 대상**이라 낙관락(@Version) 변형을 시연해야 하기 때문이다([ADR-0009](0009-stock-concurrency-design.md) 결정 1·2). 두 테이블의 version 정책 차이는 "운영 확정(ProductStock) vs 방식 비교 대상(HotDealStock)"의 차이다.
- **경계(문서화)**: 상품 재고 행 *자체*의 고경합(인기 상품 일반 판매 경로)은 스코프 밖이다(일반 판매 경로 없음). 그 행이 부하 테스트에서 단일 행 쓰기 한계(~500 QPS)에 부딪히면 결정 1대로 상품 재고를 여러 버킷으로 쪼개 분산한다(Alibaba/Shopify) — 본 프로젝트는 단일 행 + 단일 창고.

## 재고 표시 정책 — 일반 가용 vs 핫딜 잔여

재고 수는 보는 자리에 따라 **두 가지**이고, 섞으면 손님에게 틀린 수량이 보인다.

| 수량 | 정의 | 출처 | 노출 대상 |
|---|---|---|---|
| **상품 가용** | 실물 − 예약 (ATP) | `ProductStock` 계산 | 내부·관리자(다음 회차를 더 열 수 있나) |
| **핫딜 잔여** | 회차에 남은 판매 가능 수 | `HotDealStock.remaining` | 손님(핫딜 상세·구매가 보는 "남은 수량") |

- 손님이 핫딜 상세/구매에서 보는 "남은 수량"은 **핫딜 잔여**(`HotDealStock.remaining`)다. 등록 때 이미 예약으로 떼어 둔 분에서 파는 것이라(결정 3), 손님 화면의 숫자는 그 회차에 갇혀 있다 — 상품 가용(실물−예약)이 아니다.
- **상품 가용**은 관리자가 다음 회차를 더 열 수 있는지 판단하는 내부 수치다. 손님 API 로 노출하지 않는다(전체 창고 재고 노출 = 운영 정보 누설). 그래서 조회 슬라이스는 핫딜 잔여만 응답에 싣고, 상품 가용은 등록 시 가용 검사(결정 3·4)에만 쓴다.

## 관련 방향 — 구매 API (슬라이스 1 확정 · 2026-06-20 상품 주소로 전환)

구매 설계는 **주문 데이터 모델(표준이 강제)과 API 주소 방식(아키텍처 선택)을 가른다** — 둘은 별개다.

- **주문 모델 = 상품 참조 + 적용 핫딜 기록 + 금액 스냅샷** (표준 강제 · 변경 없음): 커머스 주문의 라인아이템은 상품을 참조하고 적용 프로모션 id 와 주문 시점 가격을 함께 보존한다(Salesforce B2C `ProductLineItem`·commercetools·Craft Commerce). → `Order` 는 `product_id`(무엇을 샀나) + `hot_deal_id`(적용 핫딜) + `order_amount`(스냅샷)를 가진다. 계정당 1활성주문 유니크는 `hot_deal_id` 기준 유지(수량 상한은 [ADR-0005](0005-one-per-user-active-unique.md)). **이 모델은 이전 판 그대로다.**
- **API = 상품 주소 + 서버가 활성 핫딜 해소** (2026-06-20 재조사로 확정): 구매는 최상위 `POST /api/orders` 에 **`{productId, quantity}`** 를 실어 **상품**을 지정한다. 서버가 그 상품의 **현재 활성 핫딜을 해소**(상품 + 현재 시각 ∈ [startAt, endAt] + status=ACTIVE 조회, 없으면 `NO_ACTIVE_DEAL`)해 핫딜가를 적용하고 `HotDealStock` 을 차감한다. 손님은 핫딜 id 를 들고 다니지 않고 "이 상품을 산다"만 표현한다.
- **왜 상품 주소로 뒤집었나**: 서구(Shopify Checkout·Stripe Checkout·commercetools)와 한국(쿠팡·29CM) 커머스를 재조사한 결과, 손님이 보는 구매 단위는 **상품**이고 특가/프로모션 적용은 **서버 책임**이 표준이다. 손님에게 "핫딜 id"라는 내부 식별자를 쥐여 주는 주소는 흔치 않다. 이전 판(핫딜 주소 `{hotDealId}`)은 "읽기 API 가 핫딜 중심이라 일관"을 근거로 들었으나, **읽기가 핫딜 중심인 것과 쓰기(구매) 주소를 상품으로 두는 것은 양립**한다 — 상세는 핫딜로 읽고(`GET /api/hotdeals/{id}`), 구매는 상품으로 보낸다. 상품 주소가 요구하는 "상품→활성 핫딜 해소" 간접층은 비용이 아니라 **표준 그 자체**다.
- **REST 형태**: 주문은 사용자가 소유하고 `order_no` 로 독립 조회되며 핫딜 취소 후에도 PAID 주문이 유효([ADR-0007 결정2](0007-hotdeal-state-operations.md))해 핫딜 종속 하위 리소스가 아니므로, `POST /api/hotdeals/{id}/orders` 가 아니라 최상위 `/orders` + 본문 참조가 맞다(Stripe·Shopify·commercetools 공통).
- **새 거부 케이스**: 상품에 활성 핫딜이 없으면 `NO_ACTIVE_DEAL`(코드·Status 는 슬라이스 1에서 확정). 활성 핫딜 해소 로직·계정당 1활성주문 유니크 충돌 등 상세는 구매 슬라이스 1 설계 문서에서 다룬다.

## 스코프 기준 — 타 도메인은 어디까지

핫딜 주문이 의존하는 타 도메인은 **"실제 커머스 백엔드 개발자가 '핫딜 주문 구현'을 맡았을 때 그 도메인에 대해 이미 있어야/알아야 정상인 수준"** 으로만 현실적으로 깔아 둔다(전체 커머스 X). 그래서 Product는 진짜 재고(`ProductStock`)를 갖되, 다중 창고·WMS·일반 판매 경로·재고 입고 운영은 스코프 밖.

## 보류

- 안전재고·다중 창고·입고예정·예약 만료 — 필요 시 후속.
- 상품 재고 초기 입고 — 현재는 시드/픽스처, 운영 입고 API는 후속.
- 재고 변동 **원장**(모든 변동을 한 줄씩 추가 기록해 잔액을 합산·감사) — 슬라이스 2(만료 복원)에서 PRD "장부 일치 100%" 불변식과 함께 검토.

## 출처

- [Shopify Engineering — 재고 예약 확장 (MySQL, 2026)](https://shopify.engineering/scaling-inventory-reservations)
- [Alibaba — 고동시성 재고 플래시세일: 독립 재고 테이블·상품별 분산·버킷 분할](https://developer.aliyun.com/article/754897)
- [HotWax — 옴니채널 재고 원본·약속 가능 재고(ATP)](https://www.hotwax.co/blog/single-source-of-truth-for-inventory-availability-in-omnichannel-retailing)
- [Fluent Commerce OMS — 소프트 예약·재고 변동 원장](https://preview.community.apse2.training.fluentcommerce.com/blog/fluent-oms-inventory-accuracy-soft-reservation-guide)
- 구매 API 표준 재검증(2026-06-18):
  - [Salesforce B2C — ProductLineItem(주문 라인 = 상품 참조 + 가격 조정 + 스냅샷)](https://salesforcecommercecloud.github.io/b2c-dev-doc/docs/current/scriptapi/html/api/class_dw_order_ProductLineItem.html)
  - [commercetools — 가격·할인 개요(적용 프로모션 기록)](https://docs.commercetools.com/api/pricing-and-discounts-overview)
  - [100K QPS Seckill 아키텍처 — 분리는 dedicated Flash Sale Service(인프라)](https://medium.com/codetodeploy/building-a-100k-qps-seckill-system-a-complete-architecture-guide-52c65812c3c1)
- 구매 API 주소 재조사(2026-06-20 — 핫딜 주소 → 상품 주소로 전환): 손님이 보는 구매 단위는 상품, 특가/핫딜 적용은 서버 책임이 공통 표준
  - 서구: Shopify Checkout · Stripe Checkout · commercetools — 라인아이템(상품) 단위 구매, 프로모션은 서버가 적용
  - 한국: 쿠팡 · 29CM — 상품 페이지에서 구매, 핫딜/특가 적용은 서버 처리(핫딜 id 비노출)
