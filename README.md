# commerce — 핫딜 커머스 백엔드

선착순 한정 재고(핫딜) 상품을 트래픽 폭주 속에서도 **동시성·정합성** 있게 구매(주문 → 결제)하는 백엔드 포트폴리오. 이후 **대용량 읽기 조회**, 일부 **MSA 전환**으로 확장한다.

> 상세 계획: [`docs/project-plan.md`](docs/project-plan.md)

## 기술 스택

| 영역 | 스택 | 비고 |
|---|---|---|
| 언어 | Java 21 | 최신 LTS |
| 프레임워크 | Spring Boot 3.5.13 | 안정·레퍼런스 우선 (4.0 호환 리스크 회피) |
| 웹 서버 | Undertow | 경량 |
| 영속성 | Spring Data JPA + QueryDSL 5.0 | 동적 쿼리·프로젝션 |
| DB | MySQL 8.4 LTS | 8.0 EOL(2026.4) → 8.4 LTS |
| 마이그레이션 | Flyway | 스키마 버전 관리 |
| 캐시·락 | Redis 7 | 고트래픽 캐싱·분산락 (후속 슬라이스) |
| 매핑 | MapStruct | 엔티티 ↔ DTO |
| 문서화 | SpringDoc OpenAPI | Swagger UI |
| 테스트 | JUnit 5 + Testcontainers(MySQL) | |

> 표준 스택은 한 줄 근거만. 트레이드오프가 있던 의사결정(인증·아키텍처·PG·동시성 제어)은 `docs/adr` 에 별도 기록 예정.

## 로컬 실행

```bash
docker-compose up -d      # MySQL 8.4 + Redis 7
./gradlew bootRun         # http://localhost:8080
```

- Swagger UI: `http://localhost:8080/swagger-ui.html`

## 빌드 / 테스트

```bash
./gradlew compileJava     # 컴파일 (QueryDSL Q클래스 생성)
./gradlew test            # 테스트 (Testcontainers — Docker 필요)
```

---

<details>
<summary>스파르타 강의 제출 방법</summary>

- 작업 브랜치: `work/{팀번호}-{영문 이름}` (예: `work/1-john-doe`)
- 제출 브랜치: `project/{팀번호}-{영문 이름}` 로 PR 생성 → 리뷰 → 병합

</details>
