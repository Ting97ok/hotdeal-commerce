# 핫딜 선착순 운영 — 실무 리서치 (검증된 사례)

> 목적: 핫딜 선착순 구매 백엔드의 **요구사항 가설**을 실제 플래시세일(flash sale — 짧은 기간·한정 수량 특가로 트래픽이 한순간에 몰리는 행사) 운영 현실에 못 박기 위한 근거 수집.
> 방법: deep-research(다중 출처 fan-out → 적대적 검증). 6개 각도 · **28개 출처 · 104개 주장 추출 → 25개 적대 검증(2/3 반박 시 폐기) → 25개 전부 확인, 0개 폐기 → 16개로 합성**.
> 출처 편향·잔여 근거 공백은 아래 '9. 재고 선점·TTL·결제 정합성 — 1차 자료 재발굴' 항목에 정직하게 명시. 가설 토론용 근거 자료이며, 설계 결정은 [erd.md](erd.md)·도메인 `api-design` 에서 확정.

---

## 0. 한 줄 결론

트래픽이 한순간에 몰리는 선착순 플래시세일은 **두 개의 독립된 백엔드 문제**다 — ① 정문 트래픽 제어(가상 대기열), ② 정확한 선착순 카운트/재고 차감(**오버셀(재고·한도보다 많이 팔리거나 발급되는 일) 0건**). 우리 1차 주연은 **②**이고, ①은 분리된 front-door 계층(스트레치/Could).

---

## 1. 정확성(오버셀 0)이 성능과 동급의 1급 요구사항

- 올리브영 2025-06 세일: 선착순 쿠폰을 '성공 응답 후 비동기 워커' 구조로 운영 → 워커 실패로 **약 0.014% 미발급**(일별 0.004~0.037%). 통계적으론 낮지만 **CS 폭증·브랜드 신뢰 하락**으로 "치명적 비즈니스 리스크". 부하테스트의 **1.62% 과발급**도 문서화 후 0%로 교정. [olive-fcfs]
- 함의: 선착순에서 "거의 맞음"은 실패다. **오버셀 0 / 거짓 성공 0** 이 헤드라인 정확성 요구 — 올리브영의 '미발급'은 성공 응답 후 미지급, 즉 **거짓 성공**이다(재고가 남은 채 정직하게 실패하는 "덜 팔림"과 구분 — [주문 ADR 6절](../adr/order.md)).

## 2. 핵심 동시성 결함 = TOCTOU (read-then-write 비원자성)

- 결함: 'GET 후 INCR' 처럼 **확인-결정-쓰기** 가 분리되면 두 요청이 같은 값을 읽고 둘 다 통과 → 한도 초과(올리브영: count=99 통과 → 101 재확인). [olive-fcfs][redis]
- 단일 `INCR` 은 Redis 단일 스레드라 원자적이지만, **조건 분기가 낀 차감**은 원자화해야 한다 → Lua 스크립트(EVAL — Redis 가 여러 명령을 한 덩어리로 원자 실행하는 스크립트)로 묶거나 도메인 설계(dual-counter). 토스: "Redis Single-Thread 라 Increment 는 Thread-Safe". [toss]
- **이게 정확히 우리 문제다**: `Stock.decrease(qty)` = 잔여 확인 → 차감(check-decide-write). 우리 동시성 4방식(낙관 `@Version` / 비관 `select … for update` / Redis Lua / 분산락)은 전부 이 TOCTOU 를 직렬화하는 서로 다른 해법.
  > 갱신 노트(2026-06-20): 이후 **원자적 조건부 UPDATE**(`WHERE remaining >= qty` 조건부 차감 한 문장)를 5번째 방식으로 추가 — 저경합 ProductStock 운영 채택([재고 동시성 ADR 4절](../adr/concurrency.md))·고경합 HotDealStock 벤치마크 합류([재고 동시성 ADR](../adr/concurrency.md)). 본문 "4방식"은 리서치 시점 기록.

## 3. 성능 ↔ 정확성 트레이드오프 (벤치마크 서사의 실선례)

- 올리브영: check-and-increment 를 **Lua 로 전면 원자화 → 정확성 0건 달성하나 ~21% 성능 저하**(고트래픽엔 무리) → **이중 카운터(dual-counter)** 로 전환: 상품당 키 2개(`count`=실제 발급 한도, `countReq`=요청량), 요청 카운터를 **먼저** 올려 초과 동시요청을 차단하고 실제 카운터는 통과분만 증가 → **~8% 비용에 정합성 100%**. [olive-fcfs]
- 함의: 우리 3주차 "4방식 벤치마크 → 개선"은 **실무에서도 그대로 일어나는 트레이드오프 탐색**. "정확성만"은 쉽고 **"정확성+성능"이 어렵다**가 핵심 서사.

## 4. Redis 운영 현실

- **병목 = 단일 메인 스레드 CPU**(EngineCPUUtilization). 단일 스레드라 인스턴스 스케일 업은 단일 vCPU 성능을 못 바꿔 효과 없음 → **replica 추가 스케일 아웃** 으로 분산(읽기 우세일 때). Wonderwall 10만 동시 대기열 부하테스트(1초 1회 폴링 모델). [wonder][aws]
- **비동기 버퍼링으로 폭주 흡수**: 올리브영 Redis List(RPush/LPop)로 0시·12시 폭주 버퍼링, Pub/Sub 으로 요청당 단일 워커 지정(초기 4워커 중복발급 4회 → 1회). 이후 RabbitMQ Fanout 다중 큐로 처리량 12~15h→5~6h. [olive-async][olive-mess]
- **분산락(RedLock)도 멱등(같은 요청이 중복돼도 한 번만 처리됨) 다층의 한 층**: 토스는 라이브쇼핑 중복 포인트 지급을 RedLock 분산락 + 멱등 원장 + Kafka throttling 으로 다층 방어 → 우리 4방식 중 **분산락(Redisson)** 의 실선례. [toss]
- 토스: 로컬 캐시 카운팅 후 ScheduleJob 으로 Redis Flush(성능↑) — **단 hard capping 이 아니라** 선착순 개념과 맞는지 확인 필요(정확성-성능 트레이드오프). [toss]

## 5. 가상 대기열 (정문 트래픽 제어 = 별도 문제)

- **Redis Sorted Set 기반 FIFO 가상 큐**(메시지 큐 아님): G마켓 Redcarpet — Timestamp=Score, userId+상품번호=Member. 실시간 랭킹 때문에 Kafka 부적절. [gmarket]
- **임계치 초과 시 상품별 자동 발동**(항상 켜짐 아님): 임계치 이하면 바로 상품 노출, 이상이면 큐 발동. [gmarket]
- **admission rate throttle**: 초당 입장 인원으로 백엔드 유입 속도 제어(G마켓 관리자 툴 실시간 조정 / Cloudflare 동시활성·분당신규 두 한도). [gmarket][cf]
- **엣지 holdback**: Fastly/Cloudflare 는 origin 도달 전 엣지에서 대기 페이지 서빙, 선두만 통과. FIFO 는 카운터 2개(now serving / 다음 번호표) + 서명 쿠키. [fastly]
- **분산 카운터 = 결과적 일관성 + 보수적 슬롯 분배** 로 오버셀 억제(중앙 단일 카운터 회피). [cf]
- 함의: 대기열은 우리 코어(재고 동시성)와 **분리된 계층**. plan 에서도 Could. 코어를 먼저 깊게 판 뒤 스트레치로.

## 6. rate limiter 선택

- Redis 권장 기본 = **슬라이딩 윈도우 카운터**(낮은 메모리·경계 버스트 없음). 고동시성에선 **Lua > MULTI/EXEC·WATCH** — WATCH 낙관락은 경합(=플래시세일 스파이크)이 심할수록 재시도 폭주로 퇴화. [redis]

## 7. 선착순 + 수량 한정 = 실제 모델 (추첨과 구분)

- **검증**: 카카오톡 선물하기 '나에게 선물' = 매일 인기상품을 **한정 수량 선착순 판매**(시간차 드롭: 100개씩 3차례, 사전 알림톡, 실시간 구매 랭킹). 추첨/응모 증거 0건. [kakao]
- **인당 제한 실재**(예시): 위메프 타임딜 페이지 타이틀 "균일가 선착순 한정수량 **주문건당 1개 구매제한**" (※ 미검증 출처 — 이후 10.2에서 1차 출처로 보강 완료).
- **추첨(draw)은 별개 모델**: 나이키 SNKRS 는 추첨형(봇 방어 목적). 선착순과 운영 분기.

## 8. 우리 프로젝트 매핑 (가설에 어떻게 쓰나)

| 리서치 발견 | 우리 설계 반영 |
|---|---|
| 오버셀 0 = 1급 정확성 | 헤드라인 불변식. 동시성 테스트(재고 N → 정확히 N·오버셀 0)가 1급 게이트 |
| TOCTOU = check-decide-write | `Stock.decrease` 의 본질. 4방식이 같은 결함의 다른 해법 |
| Lua 21% ↔ dual-counter 8% | 3주차 벤치마크 서사 = 정확성+성능 트레이드오프 (실선례 인용) |
| Redis 단일스레드·replica | Redis 방식의 병목·확장 논의 근거 |
| 토스 RedLock | 4방식 중 분산락의 실선례 |
| 비동기 버퍼링·Kafka | MSA 전환(결제 후속) + 폭주 흡수 근거 |
| 가상 대기열 = 별도 front-door | 코어 분리, 스트레치(Could) |
| 선착순+수량한정 실모델 | "1인 1개" 결정의 선례 |

## 9. 재고 선점·TTL·결제 정합성 — 1차 자료 재발굴 (추출 claim · 미검증)

> 아래는 1차 리서치가 **fetch 단계에서 이미 추출**했으나, 적대 검증 예산(상위 25개)이 동시성·대기열에 쓰여 **합성 16개에서 빠진** claim 들이다. 1차 출처(Shopify·OneUptime·우아한형제들 등)에서 추출됐으나 **3표 적대검증은 안 거침** → 신뢰도는 "1차 출처 인용" 수준 — 적대 검증을 통과한 위 1~7번 항목과 구분해서 본다. 출처 품질도 함께 표기.

### 9.1 재고 선점(예약) 2단계 모델 + TTL
- **2단계 reserve→confirm**: 단순 차감은 동시성에 깨짐 → **임시 예약(reserve) → 결제 성공 후 확정(confirm)** + 미결제 예약은 TTL(Time To Live — 정해진 시간이 지나면 자동 만료)로 자동 해제. [oneuptime][flashsale]
- **available/reserved 분리**: 가용 수량 `decrby`(선차감) + 예약 수량 `incrby` 로 원자적 선점(확정 전 미리 빼둠). [oneuptime]
- **TTL 값**: 결제 제한시간 보통 **5~15분**(카테고리/고객별 상이). 예시 `reservation_expires_at` 10분, `RESERVATION_TTL=900`(15분). [oneuptime][flashsale]
- **주문이 만료 시각 보유**: order 가 `reservation_expires_at` 을 들고 → 그 시각까지 미결제면 자동 취소 + 재고 반환. [flashsale]

### 9.2 미결제 만료 처리 — 두 방식
- **Redis 키 TTL 자동 만료** vs **백그라운드 잡/주기적 cleanup 이 마감 지난 예약을 sweep 해 가용 풀로 반환**. [oneuptime]
- 만료와 결제 콜백의 레이스는 멱등 복원(9.3)으로 흡수.

### 9.3 복원 멱등성 (이중 복원 방지) — 우리 `(user×hotdeal)` 유니크와 같은 통찰
- **우아한형제들(카카오 선물하기류)**: 재고사용량을 **Redis Set 에 유니크 구매번호로 적재** → Set 중복 비허용으로 **동일 구매 중복 차감 방지(멱등)**. 취소/실패 시 **event-queue 로 복원 이벤트 비동기 발행**, Set 에 구매번호가 있을 때만 차감 → **멱등 복원**. (전체 재고=RDB, 실시간 사용량=Redis 분리, 트랜잭션 시 RDB sync) [woowahan]
- **flash-sale 불변식**: 결제 실패 시 단위를 풀로 되돌려야 — 안 그러면 **결제 실패 1건당 미판매 재고 1개 손실**. [flashsale]

### 9.4 선점 상태를 어디에 두나 — Shopify 반례 (Redis → MySQL)
- Shopify 는 예약을 **Redis → MySQL 로 이전**: 예약과 재고 원장이 **다른 시스템에 있어 단일 원자 연산으로 못 묶여 과/미판매 둘 다 발생** → MySQL **ACID 트랜잭션으로 reserve+claim 을 함께** 감쌈. [shopify]
- **오버셀 0**: 재고를 **물리 단위당 1행**(10개 = 10행) + **`SELECT … FOR UPDATE SKIP LOCKED`** → 동시 예약이 같은 행 경합 대신 서로 다른 가용 행을 잡음. [shopify]
- 플래시세일 스파이크: item/location 당 **가용 행 풀 1,000개 상한**, 소진 시 reserve 경로가 **인라인 보충**. [shopify]
- → "Redis TTL 예약이 기본"이라는 가정을 압박하는 **내구성·정합성 트레이드오프** 1차 사례. 우리 4방식 벤치마크의 **비관락(SKIP LOCKED) variant** + "선점 상태의 거처(Redis vs RDB)" 논거로 직접 활용.

### 9.5 정시 오픈 · 매진 처리 (핫딜 상태 모델 직결)
- **정시 오픈/일일 리셋**: 쿠팡 골드박스 = 매일 **오전 7시 정시 오픈**, 1일 한정특가, **수량 소진 시 조기 마감**(아니면 다음날 7시), 와우 회원 한정. 카카오 = 정시 오픈 + 한정수량 선착순 + 사전 알림톡. [coupang][kakao]
- **매진 = 재고 0 도달 시 즉시 `unavailable` 마킹**(빠른 거절·노출용). [flashsale]
- → 핫딜 상태 모델 반영: **진행 단계는 판매 기간(정시 오픈)·재고로 그때그때 판단**하되, **매진 시 품절 표시**로 빠른 거절/조회를 보완(판단 원본 + 표시 하이브리드).

### 9.6 결제수단·어뷰징 (결제 승인·만료 복원 슬라이스 보강용)
- **Nike SNKRS**: 결제수단 **사전 바인딩**(저장 카드/카카오페이만, 할부 불가) + 추첨 한정수량 + **결제 유효성 게이트**(잔고부족·만료 시 주문 미처리 → 점유 해제). [snkrs]
- **인터파크**: 어뷰징 사후 제재 ladder(1차 3개월 정지+전체취소 → 3차 영구정지), **탐지 시 자동 주문취소·복원** + 계정별 어뷰징 이력 로깅. 무통장 미입금 → 결제수단 3개월 제한. [interpark]

### 9.7 잔여 공백
- 쿠팡/11번가/광군제의 **내부 동시성 구현 디테일**, 캡차·디바이스 핑거프린트 구체, 29CM(결제 후 재고부족 실패 모드)는 1차에서 검색 표면에만 잡힘 → 결제 승인 슬라이스 진입 시 타깃 보강. 그래도 **선점·TTL·복원·정시오픈·매진 축은 위 재발굴로 설계 착수에 충분**.

## 10. 핵심 가설 보강 출처 (타깃 검색 — 2026-06-11)

> 위 1~7(검증)·9(재발굴)에서 **근거 등급이 낮았던 가설 4건**을 공식·1차 출처로 보강한 결과.

### 10.1 정시 오픈·일일 리셋 — 쿠팡 골드박스
- **쿠팡 공식 채널**(페이스북 Coupang.korea 게시물): ["매일 아침 7시, 새로운 골드박스"](https://www.facebook.com/Coupang.korea/posts/2657872090892615) — 정시 오픈·일일 리셋의 1차 확인.
- 2차 보조: [골드박스 안내 정리](https://wannazone.co.kr/promotion/132) — 매일 오전 7시·한정 수량·소진 시 조기 마감.

### 10.2 인당 구매 제한 — "1인 1개"류의 실재
- **우아한형제들 기술블로그**([한정수량 재고 시스템](https://techblog.woowahan.com/2709/), 1차 — 9.3에서 인용한 글): 시스템 기능으로 **"인당 구매제한수량"** 이 실재.
- 2차: [알리익스프레스 타임딜 규칙 정리](https://www.alipress.kr/2022/10/aliexpress-time-deals/) — "1인당 1일 1개" · 위메프 타임딜 "주문건당 1개"(7절).
- 종합: 인당 구매 제한은 한정수량 특가의 **표준 장치**고, 1인 1개는 그중 가장 엄격한 설정값.
  > 갱신(2026-06-20): 이후 다층 제한(계정당 1활성주문 + 주문당 `maxPerOrder` + 총량 `maxPerAccount`)으로 확장 — [주문 ADR 4·5절](../adr/order.md). 본문 "1인 1개"는 조사 시점 기록.

### 10.3 선점 + 결제 제한시간 — 예약형의 공식 확인
- **인터파크(NOL 티켓) 공식 FAQ**([좌석 선점 안내](https://help.interpark.com/ticket/faq?categoryDetail=TICKET_TICKET_02&category=TICKET_TICKET&article=1550)): 좌석 선택 시 선점 — "좌석 보호와 공정한 예매 기회를 위해 **시간을 제한**(타이머 표시), **시간 내 결제 미완료 시 선점 좌석 해제**". 구체 분 수는 상품 정책별 상이.
- → 우리 모델(주문 선점 → 제한시간 → 만료 해제·복원)과 구조가 1:1로 일치하는 공식 1차. 제한시간을 고정 상수가 아닌 **정책 파라미터**로 두는 것까지 동일.

### 10.4 FK 제약 미사용 관행
- **SK㈜ C&C 공식 테크블로그**: [Foreign Key 없이 구축하는 관계형 DB 시스템](https://engineering-skcc.github.io/oracle%20tuning/foreign_key_%EC%97%86%EC%9D%B4_%EA%B5%AC%EC%B6%95%ED%95%98%EB%8A%94_DB/) — 대량 데이터의 FK 검사 비용·운영 유연성 관점(1차).
- **Alibaba Java 개발 매뉴얼**([p3c](https://github.com/alibaba/p3c)): "외래키·캐스케이드 금지 — 외래키 개념은 전부 애플리케이션 계층에서 해결"을 **강제(Mandatory) 규칙**으로 명문화한 대규모 실무의 대표 사례.
- MSA 보조: [서비스별 DB 분리 시 경계 넘는 FK 불가](https://giljae.medium.com/%EB%A7%88%EC%9D%B4%ED%81%AC%EB%A1%9C-%EC%84%9C%EB%B9%84%EC%8A%A4-%EC%95%84%ED%82%A4%ED%85%8D%EC%B2%98%EC%97%90%EC%84%9C-%EB%8B%A8%EC%9D%BC-%EB%8D%B0%EC%9D%B4%ED%84%B0%EB%B2%A0%EC%9D%B4%EC%8A%A4%EB%A5%BC-%EB%B6%84%EB%A6%AC%ED%95%B4%EC%95%BC-%ED%95%98%EB%8A%94-%EC%9D%B4%EC%9C%A0-2d3c274bbe39).

### 10.5 품절 후 재입고(증량)의 실재 — "수량 추가 금지가 표준"은 아님

- **올리브영 공식 "재입고 기획전"**: 품절대란 상품의 재입고를 공식 마케팅으로 운영 — [기획전 페이지](https://www.oliveyoung.co.kr/store/planshop/getPlanShopDetail.do?dispCatNo=500000101880020) (1차). 쿠팡도 품절 후 재입고가 일상 운영([커뮤니티 Q&A](https://www.a-ha.io/questions/4c6c0309dd66ab6ba812a418538a4781) — 2차).
- 단, **진행 중인 한정 선착순 딜의 수량을 증량**하는 공식 1차 사례는 미확보(검색 한계 — 정직 표기).
- → 설계 반영: "품절 후 다시 구매 가능"은 ⓐ 취소·만료 **복원** ⓑ **재입고**(증량) ⓒ **회차** 재오픈, 세 메커니즘이 실무에 병존한다. 본 설계의 증량 금지는 표준 주장이 아니라 **스코프 선택**으로 정정 — [ADR-0007](../adr/0007-hotdeal-state-operations.md).

---

## 출처 (primary 위주)

- 올리브영: [선착순 쿠폰 미발급 0%][olive-fcfs] · [쿠폰 대량발급 개선][olive-mess] · [Redis 비동기 발급][olive-async]
- 토스: [서버 증설 없이 처리하는 대규모 트래픽][toss]
- G마켓: [대기열 시스템(Redcarpet)][gmarket]
- Cloudflare: [Waiting Room queues][cf] · Fastly: [Waiting room tutorial][fastly] · Wonderwall: [가상대기열 부하테스트][wonder]
- Redis: [rate limiting howto][redis] · AWS ElastiCache(교차 확인)
- 카카오: [선물하기 한정수량 선착순][kakao]
- (9번 재발굴) [Shopify inventory reservations][shopify] · [우아한형제들 2709][woowahan] · [OneUptime Redis 재고예약][oneuptime] · [flash-sale 설계(Ajit Singh)][flashsale] · [쿠팡 골드박스][coupang] · [인터파크 anti-abuse][interpark] · [Nike SNKRS draw][snkrs]

[olive-fcfs]: https://oliveyoung.tech/2025-12-15/fcfs-coupon/
[olive-mess]: https://oliveyoung.tech/2024-12-11/oliveyoung-coupon-mess-issue/
[olive-async]: https://oliveyoung.tech/2023-08-07/async-process-of-coupon-issuance-using-redis/
[toss]: https://toss.tech/article/monitoring-traffic
[gmarket]: https://dev.gmarket.com/46
[cf]: https://blog.cloudflare.com/how-waiting-room-queues/
[fastly]: https://www.fastly.com/documentation/solutions/tutorials/waiting-room/
[wonder]: https://tech.wonderwall.kr/articles/vwrloadtest/
[redis]: https://redis.io/tutorials/howtos/ratelimiting/
[kakao]: https://www.kakaocorp.com/page/detail/11554
[shopify]: https://shopify.engineering/scaling-inventory-reservations
[woowahan]: https://techblog.woowahan.com/2709/
[interpark]: https://help.interpark.com/ticketGuide/antiAbuseRegulations.html
[snkrs]: https://www.nike.com/kr/help/a/nike-snkrs-draw
[oneuptime]: https://oneuptime.com/blog/post/2026-03-31-redis-inventory-reservation/view
[flashsale]: https://singhajit.com/flash-sale-system-design/
[coupang]: https://econsis.kr/coupang-goldbox-wow-membership-deals/
