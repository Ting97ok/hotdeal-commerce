# commerce — 핫딜 커머스 백엔드

**선착순 한정 재고를 트래픽 폭주 속에서 초과 판매 0 으로 파는 백엔드.** 재고 동시성 3전략을 같은 워크로드로 벤치마크해 운영 전략을 실측으로 고르고, 토스 결제의 거절·통신오류·미확정까지 정합성 있게 처리한다.

[![CI](https://github.com/Ting97ok/hotdeal-commerce/actions/workflows/ci.yml/badge.svg)](https://github.com/Ting97ok/hotdeal-commerce/actions/workflows/ci.yml) ![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F) ![oversell](https://img.shields.io/badge/oversell-0-brightgreen) ![tests](https://img.shields.io/badge/tests-3.5k%20lines-blue)

- **현재**: 모놀리식으로 핵심 흐름 완성 — 핫딜 등록 → 주문(재고 선점·미결제 만료) → 토스 결제 실연동 → 미확정(IN_DOUBT) 해소 스케줄러
- **다음(확정 로드맵)**: 결제 후속 처리 부분 MSA 분리 → 대용량 조회 + Redis 캐싱

> 📖 [docs/README.md](docs/README.md) — 읽는 순서·핵심 결정 요약 · **의사결정 기록** [ADR 13편](docs/adr/README.md) · 비기능 수치 [nfr](docs/design/nfr.md)

## 이 프로젝트가 증명하는 것 / 아직 증명하지 않는 것

실무 경력에 없던 고동시성·정합성 처리를 프로덕션 기준으로 직접 설계·측정한 개인 프로젝트다. 증명 범위를 정직하게 구분한다.

> 패키지명 `com.sparta` 는 시작 시점의 잔재다 — 팀스파르타 **MSA 아키텍처 과정**(고용노동부 재직자 훈련, 2026.03~07)에서 출발해 개인 저장소로 이관·확장했다(커밋 이력 보존).

**증명한다**
- 동시성 문제(확인과 쓰기 사이에 다른 요청이 끼어드는 문제)와 해법 트레이드오프 — 낙관적 락·조건부 UPDATE·Redis 3전략을 같은 워크로드로 실측 비교
- 측정으로 검증하는 방법론 — **측정 오염을 찾아 결론을 번복**했고, **데이터가 가설을 뒷받침하지 않을 때 그대로 기록**했다
- **스케일아웃의 한계와 그 원인 분리** — LB 뒤 앱 1 vs 3 실측. 락 대기 누적 8.8배 증가로 재고 행 경합을 확인하고, 락을 쓰지 않는 Redis를 대조군으로 자원 경합 몫을 갈라냈다
- 정합성·불변식 사고 — 초과 판매 0·거짓 성공 0 을 계층 방어 + 재고 장부 검증식으로
- 트레이드오프를 언어화한 의사결정 — 주제별 ADR(각 결정의 대가와 대응)

**아직 증명하지 않는다 (정직한 경계)**
- 실 프로덕션 규모·실트래픽 — 측정은 **로컬 1머신**. 다중 인스턴스도 한 머신 위 컨테이너라 **물리 분산이 아니고**, 부하기·앱·DB 를 물리적으로 나눈 측정은 미검증(다음 단계)
- 실장애 대응(카나리·롤백 실행·알람 대응) — 설계는 있으나 운영 경험 아님
- 토스는 **테스트 모드** — 실거래 아님

> 그래서 이 저장소는 "고트래픽을 겪었다"가 아니라 **"고트래픽을 설계·검증할 수 있다"** 를 보인다.

## 핵심 결과 — 재고 동시성 벤치마크

### ① 앱을 늘리면 왜 느려지나 — 원인을 둘로 분리했다

nginx 로드밸런서 뒤 앱을 1대→3대로 늘리고, 부하는 동시 1,000명·1,000건으로 **똑같이** 줬다.

![인스턴스 1→3: 조건부는 락 대기 8.8배, 락을 안 쓰는 Redis도 1.8배 악화](docs/design/images/benchmark-multi-instance.svg)

| 전략 | 앱 | 주문 API p95 | 처리율 | 행 락 대기(누적) |
|---|:--:|---|---|---|
| 조건부 UPDATE | 1 → 3 | 1.44s → **2.52s** | 7.14 → 12.43건/초 | 5.2s → **45.8s** |
| Redis + Lua | 1 → 3 | 1.10s → **1.99s** | 13.75 → 12.54건/초 | **0 → 0** |

- **재고 행 락 경합은 실재한다** — 조건부 UPDATE의 락 대기 누적이 **8.8배**로 늘었다. 대기 횟수는 985→999회로 거의 같으니, 건당 대기가 8.6배 길어진 것이다.
- **락과 무관한 요인도 실재한다** — 재고 행을 아예 건드리지 않아 락 대기가 **0인 Redis도 1.8배 느려졌다**. 한 머신에서 앱 셋·DB·Redis·부하 도구가 자원을 나눠 쓴 몫이다.
- **Redis를 대조군으로 두니 둘이 갈렸다** — 앱 3대에서 조건부 2.52s와 Redis 1.99s의 차이 **0.53s가 락 경합이 추가로 만든 몫**, 나머지는 둘이 함께 겪은 자원 경합.
- **순간을 찍는 관측으로는 안 보였다** — 2초 간격 `row_lock_current_waits`는 34번 모두 0. 누적값을 보고서야 45.8s가 드러났다. 대기가 짧고 잦으면 순간 관측은 놓친다.
- **초과 판매 0은 앱 3대에서도 성립** — 재고 10에 1,000명이 몰려도 성공은 정확히 10건.

### ② 전략 선정 — 같은 워크로드로 3전략 실측

동시 1,000명이 재고 한 행을 다투는 오픈 스파이크를 낙관적 락 · 조건부 UPDATE · Redis+Lua 로 측정해 **원자적 조건부 UPDATE** 를 운영 전략으로 확정했다 — [재고 동시성 ADR](docs/adr/concurrency.md).

![고경합 3전략 벤치마크 — 낙관적 락 성공 163 탈락, 조건부·Redis 1000 동률](docs/design/images/benchmark-strategies.svg)

- 낙관적 락은 버전 충돌로 성공 163/1000 (정확하나 비실용, 탈락). 조건부·Redis 는 1,000 동률.
- 조건부가 p95 최저(1.37s)·쿼리 1문으로 가장 단순 → 채택. SLA: 폭주 p95 ≤ 2s · 평시 ≤ 500ms.

### ③ 측정 오염 발견이 결론을 바꿨다

호스트 측정에선 Redis 가 앞서 보였으나 원인은 조건부만 겪는 SQL DEBUG 로깅 오염이었다. 격리 스택(tmpfs DB·로그 차단) 재측정에서 그 격차가 사라졌다.

![측정 환경이 결론을 바꾼 사례 — 조건부 2.25s→1.37s 급락](docs/design/images/benchmark-measurement-pollution.svg)

- 재현: `bash k6/benchmark/run.sh`(단일) · `bash k6/benchmark/run-multi.sh`(1 vs 3 인스턴스) — 일회용 컨테이너로 전략 순회 + 초과 판매 검증 자동 ([k6/README.md](k6/README.md))

## 설계 하이라이트

- **초과 판매 0 을 겹으로 방어** — 조건부 UPDATE(영향 행 수 관문) + DB CHECK 제약 + 장부 검증식 · [주문 ADR 7절](docs/adr/order.md)
- **1인 1주문을 DB 가 직렬화** — MySQL 부분 유니크 부재를 저장 생성 컬럼(`is_active`)으로 우회한 활성 유니크 · [주문 ADR 4절](docs/adr/order.md)
- **토스 호출은 트랜잭션 밖** — 선점(TX1) → confirm(TX 밖) → 결과 반영(TX2). 승인/거절/통신오류/미확정 sealed 4분기, 미확정(IN_DOUBT)은 해소 스케줄러가 토스 재조회로 확정 · [결제 ADR 4·6절](docs/adr/payment.md)
- **상태 전이 전부 조건부 UPDATE** — 결제↔만료 경합은 진 쪽이 영향 행 0 으로 그 사실을 안다 · [주문 ADR 3절](docs/adr/order.md)
- 무상태 JWT + RTR(Redis GETDEL 원자 소비) · DB FK 제약 미사용 · [참조 무결성 ADR](docs/adr/integrity.md)

## 기술 스택

| 영역 | 스택 | 비고 |
|---|---|---|
| 언어 | Java 21 | 최신 LTS |
| 프레임워크 | Spring Boot 3.5.13 | 안정·레퍼런스 우선 (4.0 호환 리스크 회피) |
| 웹 서버 | Undertow | 경량 |
| 영속성 | Spring Data JPA + QueryDSL 5.0 | 동적 쿼리·프로젝션 |
| DB | MySQL 8.4 LTS | 8.0 EOL(2026.4) → 8.4 LTS |
| 마이그레이션 | Flyway | 스키마 버전 관리 |
| 캐시·토큰 | Redis 7 | RTR 토큰 저장 · 재고 전략 비교(벤치마크) · 캐싱(후속) |
| 매핑 | MapStruct | 엔티티 ↔ DTO |
| 문서화 | SpringDoc OpenAPI | Swagger UI |
| 테스트 | JUnit 5 + Testcontainers(MySQL 8.4) | 운영 동일 마이그레이션 검증 |
| 부하 테스트 | k6 (+ Prometheus·Grafana) | [ADR-0013](docs/adr/0013-load-test-tool-k6.md) |

> 표준 스택은 한 줄 근거만. 트레이드오프가 있던 의사결정(인증·아키텍처·PG·동시성 제어)은 [docs/adr](docs/adr/README.md) 에 기록한다.

## 로컬 실행

```bash
docker-compose up -d      # MySQL 8.4 + Redis 7
./gradlew bootRun         # http://localhost:8080
```

- Swagger UI: `http://localhost:8080/swagger-ui.html`

## 빌드 / 테스트 / 벤치마크

```bash
./gradlew compileJava            # 컴파일 (QueryDSL Q클래스 생성)
./gradlew test                   # 통합 테스트 (Testcontainers — Docker 필요)
bash k6/benchmark/run.sh         # 동시성 벤치마크 (일회용 격리 스택 — Docker + k6 필요)
```
