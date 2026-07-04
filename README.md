# commerce — 핫딜 커머스 백엔드

선착순 한정 재고(핫딜)를 트래픽 폭주 속에서도 **초과 판매(오버셀) 0 · 거짓 성공 0**으로 팔고, 토스 결제의 거절·통신오류·미확정까지 정합성 있게 처리하는 백엔드 포트폴리오.

- **현재**: 모놀리식으로 핵심 흐름 완성 — 핫딜 등록/조회 → 주문(재고 선점·미결제 만료) → 토스 결제 실연동 → 미확정(IN_DOUBT) 해소 스케줄러
- **다음(확정 로드맵)**: 결제 후속 처리의 부분 MSA 분리 → 대용량 조회 + Redis 캐싱

> 📖 **문서 관문**: [docs/README.md](docs/README.md) — 읽는 순서·핵심 결정 요약 · **의사결정 기록**: [ADR 13편](docs/adr/README.md) · 계획: [project-plan](docs/project-plan.md)

## 핵심 결과 — 재고 동시성 벤치마크로 운영 전략 선정

동시 1,000명이 재고 한 행을 두고 다투는 오픈 스파이크를 낙관적 잠금 · 원자적 조건부 UPDATE · Redis+Lua 3전략으로 같은 워크로드에서 측정해, 운영 전략을 **원자적 조건부 UPDATE**로 확정했다 — [ADR-0010](docs/adr/0010-concurrency-strategy-selection.md).

| 구간 (동시 · 재고) | 결과 |
|---|---|
| 고경합 (1,000 · 2,000) | 조건부 UPDATE p95 **1.37s** ≈ Redis 1.48s (동률) · 낙관락 성공 **163/1000** (탈락) |
| 품절 경합 (1,000 · 10) | 3전략 모두 **정확히 10명 성공 · 초과 판매 0** |
| 저경합 (100 · 10) | 조건부 UPDATE p95 **266ms** 최저 |

- **측정 오염 발견이 결론을 바꿨다**: 호스트 측정에선 Redis 가 앞서 보였으나 원인은 SQL DEBUG 로깅 오염 — 격리 스택(tmpfs DB·로그 차단) 재측정에서 역전이 사라졌다 ([ADR-0010](docs/adr/0010-concurrency-strategy-selection.md) "정직한 한계").
- SLA: 폭주 p95 ≤ 2s · 평시 p95 ≤ 500ms — 실측 분포에서 도출.
- 재현: `bash k6/benchmark/run.sh` — 일회용 컨테이너 스택으로 2전략 순회 + 오버셀 검증 자동 ([k6/README.md](k6/README.md))

## 설계 하이라이트

- **초과 판매 0 을 겹으로 방어** — 조건부 UPDATE(영향 행 수 관문) + DB CHECK 제약 + 장부 검증식 · [ADR-0006](docs/adr/0006-correctness-invariants-defense-layers.md)
- **1인 1주문을 DB 가 직렬화** — MySQL 부분 유니크 인덱스 부재를 저장 생성 칼럼(`is_active`)으로 우회한 활성 유니크 · [ADR-0005](docs/adr/0005-one-per-user-active-unique.md)
- **토스 호출은 트랜잭션 밖** — 선점(TX1) → confirm(TX 밖) → 결과 반영(TX2). 결과를 승인/거절/통신오류/미확정 sealed 4분기로 접고, 미확정(IN_DOUBT)은 스케줄러가 토스 재조회로 해소 · [ADR-0008](docs/adr/0008-payment-model-pg-boundary.md)
- **상태 전이 전부 조건부 UPDATE** — 결제↔만료 경합은 어느 쪽이 이기든 진 쪽이 영향 행 0 으로 그 사실을 안다 · [ADR-0004](docs/adr/0004-stock-reservation-lifecycle.md)
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

> 표준 스택은 한 줄 근거만. 트레이드오프가 있던 의사결정(인증·아키텍처·PG·동시성 제어)은 [docs/adr](docs/adr/README.md) 에 기록되어 있다.

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

---

<details>
<summary>스파르타 강의 제출 방법</summary>

- 작업 브랜치: `work/{팀번호}-{영문 이름}` (예: `work/1-john-doe`)
- 제출 브랜치: `project/{팀번호}-{영문 이름}` 로 PR 생성 → 리뷰 → 병합

</details>
