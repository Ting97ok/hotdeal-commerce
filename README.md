# commerce — 핫딜 커머스 백엔드

**선착순 한정 재고를 트래픽 폭주 속에서 초과 판매(오버셀) 0 으로 파는 백엔드.** 재고 동시성 3전략을 같은 워크로드로 벤치마크해 운영 전략을 실측으로 고르고, 토스 결제의 거절·통신오류·미확정까지 정합성 있게 처리한다.

[![CI](https://github.com/Ting97ok/hotdeal-commerce/actions/workflows/ci.yml/badge.svg)](https://github.com/Ting97ok/hotdeal-commerce/actions/workflows/ci.yml) ![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F) ![oversell](https://img.shields.io/badge/oversell-0-brightgreen) ![tests](https://img.shields.io/badge/tests-3.5k%20lines-blue)

- **현재**: 모놀리식으로 핵심 흐름 완성 — 핫딜 등록 → 주문(재고 선점·미결제 만료) → 토스 결제 실연동 → 미확정(IN_DOUBT) 해소 스케줄러
- **다음(확정 로드맵)**: 결제 후속 처리 부분 MSA 분리 → 대용량 조회 + Redis 캐싱

> 📖 [docs/README.md](docs/README.md) — 읽는 순서·핵심 결정 요약 · **의사결정 기록** [ADR 13편](docs/adr/README.md) · 비기능 수치 [nfr](docs/design/nfr.md)

## 이 프로젝트가 증명하는 것 / 아직 증명하지 않는 것

실무 경력에 없던 고동시성·정합성 처리를 프로덕션 기준으로 직접 설계·측정한 개인 프로젝트다. 증명 범위를 정직하게 구분한다.

> 패키지명 `com.sparta` 는 시작 시점의 잔재다 — 팀스파르타 **MSA 아키텍처 과정**(고용노동부 재직자 훈련, 2026.03~07)에서 출발해 개인 저장소로 이관·확장했다(커밋 이력 보존).

**증명한다**
- 동시성 문제(TOCTOU — 확인·결정·쓰기 비원자)와 해법 트레이드오프 — 낙관락·조건부 UPDATE·Redis 3전략을 같은 워크로드로 실측 비교
- 측정으로 검증하는 방법론 — 벤치마크 설계 + **측정 오염 발견 → 결론 번복**
- 정합성·불변식 사고 — 오버셀 0·거짓 성공 0 을 계층 방어 + 장부 검증식으로
- 트레이드오프를 언어화한 의사결정 — ADR 13편(결정 요약 헤더 + 설계노트)

**아직 증명하지 않는다 (정직한 경계)**
- 실 프로덕션 규모·실트래픽 — 측정은 **로컬 1머신**, 다중 노드 분산은 미검증(다음 단계)
- 실장애 대응(카나리·롤백 실행·알람 대응) — 설계는 있으나 운영 경험 아님
- 토스는 **테스트 모드** — 실거래 아님

> 그래서 이 저장소는 "고트래픽을 겪었다"가 아니라 **"고트래픽을 설계·검증할 수 있다"** 를 보인다.

## 핵심 결과 — 재고 동시성 벤치마크

동시 1,000명이 재고 한 행을 다투는 오픈 스파이크를 낙관락 · 조건부 UPDATE · Redis+Lua 3전략으로 같은 워크로드에서 측정해 **원자적 조건부 UPDATE** 를 운영 전략으로 확정했다 — [ADR-0010](docs/adr/0010-concurrency-strategy-selection.md).

![고경합 3전략 벤치마크 — 낙관락 성공 163 탈락, 조건부·Redis 1000 동률](docs/design/images/benchmark-strategies.svg)

- 낙관락은 버전 충돌로 성공 163/1000 (정확하나 비실용, 탈락). 조건부·Redis 는 1,000 동률.
- 조건부가 p95 최저(1.37s)·쿼리 1문으로 가장 단순 → 채택. SLA: 폭주 p95 ≤ 2s · 평시 ≤ 500ms.

**측정 오염 발견이 결론을 바꿨다** — 호스트 측정에선 Redis 가 앞서 보였으나 원인은 조건부만 겪는 SQL DEBUG 로깅 오염이었다. 격리 스택(tmpfs DB·로그 차단) 재측정에서 역전이 사라졌다.

![측정 환경이 결론을 바꾼 사례 — 조건부 2.25s→1.37s 급락](docs/design/images/benchmark-measurement-pollution.svg)

- 재현: `bash k6/benchmark/run.sh` — 일회용 컨테이너 스택으로 2전략 순회 + 오버셀 검증 자동 ([k6/README.md](k6/README.md))

## 설계 하이라이트

- **초과 판매 0 을 겹으로 방어** — 조건부 UPDATE(영향 행 수 관문) + DB CHECK 제약 + 장부 검증식 · [ADR-0006](docs/adr/0006-correctness-invariants-defense-layers.md)
- **1인 1주문을 DB 가 직렬화** — MySQL 부분 유니크 부재를 저장 생성 칼럼(`is_active`)으로 우회한 활성 유니크 · [ADR-0005](docs/adr/0005-one-per-user-active-unique.md)
- **토스 호출은 트랜잭션 밖** — 선점(TX1) → confirm(TX 밖) → 결과 반영(TX2). 승인/거절/통신오류/미확정 sealed 4분기, 미확정(IN_DOUBT)은 해소 스케줄러가 토스 재조회로 확정 · [ADR-0008](docs/adr/0008-payment-model-pg-boundary.md)
- **상태 전이 전부 조건부 UPDATE** — 결제↔만료 경합은 진 쪽이 영향 행 0 으로 그 사실을 안다 · [ADR-0004](docs/adr/0004-stock-reservation-lifecycle.md)
- 무상태 JWT + RTR(Redis GETDEL 원자 소비) · DB FK 제약 미사용 · [ADR-0003](docs/adr/0003-no-db-fk-constraints.md)

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
