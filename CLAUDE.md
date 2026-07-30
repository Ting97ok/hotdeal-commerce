# CLAUDE.md

이 파일은 Claude Code가 commerce 저장소에서 작업할 때 필요한 핵심 컨벤션을 제공합니다.
상세 구현 가이드는 `/api-impl`, 설계 워크플로우는 `/api-design` 스킬을 참조하세요.

> **경로별 상세 규칙**: `.claude/rules/` 에 계층별 코딩 패턴이 분리되어 있으며, 작업 파일 경로에 맞는 규칙이 자동 로딩됩니다.
> **구현 상세 가이드**: @.claude/skills/api-impl/entity-dto-patterns.md, @.claude/skills/api-impl/querydsl-guide.md

## 하네스: Commerce Dev Flow

**목표:** 신규 도메인 API 개발, 기존 코드 수정, 설계 단독 작업을 6명 에이전트(domain-analyst / backend-architect / api-designer / api-implementer / code-reviewer / qa-validator)로 자동 조율하여 컴파일 통과 + 설계-구현 정합성 + 계층 위반 0건까지 보장.

**트리거:** 도메인 개발/API 개발/신규 도메인/기존 코드 수정/설계 문서 작성/구현 요청 시 `commerce-dev-flow` 스킬을 사용한다. "재실행", "리뷰만 다시", "검증해줘" 같은 후속 요청도 동일 스킬이 처리한다. 단순 질문(컨벤션 조회, 코드 의미 설명)은 직접 응답.

**구성:**
- 에이전트 (`.claude/agents/`): domain-analyst, backend-architect, api-designer, api-implementer, code-reviewer, qa-validator
- 스킬 (`.claude/skills/`): commerce-dev-flow(오케스트레이터), api-design, api-impl, domain-context, qa-validation
- 중간 산출물: `_workspace/{01..05}_*.md` (감사 추적용 보존)

**TDD 표준 하 사용 범위:** commerce-dev-flow 는 "TDD 진입 전(분석·설계·엔티티/마이그레이션)"과 "TDD 종료 후(리뷰·QA)"만 담당한다. Repository/ExceptionCode/Service/Controller 는 mattpocock `tdd` 스킬의 vertical-slice TDD 로 진행(각 메서드/enum 존재가 테스트로 정당화). 세부는 [.claude/rules/commit-checkpoint.md](.claude/rules/commit-checkpoint.md).

## 작업 진행 순서

1. **리뷰 우선**: 요청의 의문점/잘못된 점을 먼저 검토·피드백
2. **리뷰 완료 확인**: 의문점 해결을 사용자에게 확인
3. **설계 문서 선행**: 신규 도메인/API 작업 전 `docs/{도메인}/api-design*.md` 를 먼저 작성(`/api-design` 스킬). 기존 주차 문서(`docs/week{n}-api-design*.md`)는 그대로 두고 신규는 도메인 단위.
4. **작업 진행**: 설계 확정 후 vertical TDD 사이클(RED→GREEN→Docs)로 구현. 세부는 commit-checkpoint.md

## 프로젝트 개요

Spring Boot 3.5.13 + Java 21 **모놀리식** 커머스(핫딜) 시스템 — 고트래픽 동시성 처리 + **부분 MSA 전환**(결제 후속 처리)을 목표로 한다. Undertow, MySQL 8.4 + Flyway, Redis(Session/Cache), JPA + QueryDSL, MapStruct, SpringDoc OpenAPI, JaCoCo. 인증은 무상태 JWT(RTR).

### 도메인 구조

단일 모듈(`rootProject.name = 'commerce'`), 패키지 `com.sparta.msa.commerce.domain.{도메인}`.
도메인: **auth·user·product·stock·hotdeal·order·payment**.

최상위 축은 셋이다.

| 패키지 | 담는 것 |
|---|---|
| `domain/{도메인}` | 비즈니스 개념. 외부 연동의 **계약 인터페이스**도 그 능력이 필요한 도메인에 둔다 |
| `infrastructure/{역할}/{벤더}` | 벤더 구현·전송·요청 응답 타입·그 설정 (예: `infrastructure/paymentgateway/toss`) |
| `global` | 프레임워크 횡단 — config·security·exception·response·entity |

## 아키텍처

```
controller/ → facade/ → service/ → repository/
                        entity/ | dto/ | mapper/ | exception/
```

- **신규 도메인은 4계층**(Controller → Facade → Service → Repository). Facade 전면 도입 — 단순 CRUD 도 Facade 경유.
- **지원 도메인(user·product·stock)은 Controller 없이 Service 까지만** — 타 도메인 Facade 가 진입점. auth·hotdeal·order·payment 는 Facade 4계층(전환 강제하지 않음).
- Facade 는 타 도메인 Service 호출(Repository 직접 금지) + Response 조립. Service 는 자기 Repository + 같은 도메인 공통 Service 만(타 도메인 Service 직접 호출 금지 → Facade 경유). 상세 [.claude/rules/service.md](.claude/rules/service.md).

### 패키지 배치

- 한 계층만 쓰는 것은 그 계층 아래, 둘 이상이 쓰면 도메인 루트. `dto` 는 controller·facade·mapper·service·entity 5계층이 써서 루트에 둔다.
- **경계를 넘나드는 데이터 타입은 `dto/`** — web 경계는 `dto/request`·`dto/response`, 외부 시스템 경계는 `client/dto`. 엔티티와 그 상태는 경계를 넘는 데이터가 아니라 도메인 모델이라 `entity/`.
- **enum 은 별도 폴더(`constant/`·`enums/`)로 모으지 않고 그것을 쓰는 타입과 같은 자리에 둔다** — `PaymentStatus` 는 `entity/`, `PgPaymentStatus` 는 `client/dto/`, `{Domain}ExceptionCode` 는 `exception/`.
- 요청 DTO 를 command 로 다시 매핑하지 않는다 — 인바운드 입구가 REST 하나라 대부분 필드가 같은 복사본이 되고, Bean Validation 이 두 곳으로 갈린다. 입구가 둘 이상(같은 Facade 메서드를 Kafka 컨슈머도 호출)이 되면 그때 도입.

## 네이밍 컨벤션

- Controller: `{Domain}{Role}Controller` / `{Role}{Domain}Controller` (혼재 허용 — 도메인 내 일관성)
- Facade(신규 도메인): `{Domain}{Role}Facade` / `{Domain}Facade`
- Service: 공통 `{Domain}Service`, 역할별 `{Domain}AdminService`/`{Domain}UserService`, 기능별 `{Domain}QueryService` 등
- Entity: `{Domain}`(단순명) / Repository: `{Domain}Repository`(+`{Domain}RepositoryCustom`/`...CustomImpl`)
- Exception: 단일 `DomainException` + 도메인 `{Domain}ExceptionCode` enum
- DTO(record): Request `{Action}{Domain}Request`, Response `{Action}{Domain}Response` / `{Domain}{용도}Response`
- 외부 연동: 계약 인터페이스 `{역할}Client` / 어댑터 구현 `{벤더}{도메인}Client` / HTTP 전송 `{벤더}HttpClient` (예: `PaymentGatewayClient` / `TossPaymentClient` / `TossHttpClient`) — 역할·근거는 [결제 ADR 3절](docs/adr/payment.md)
  - 위치: 계약은 `domain/{도메인}/client/`(계층 이름) + 그 결과 타입은 `client/dto/`, 벤더 구현·전송·요청 응답 타입·설정은 `infrastructure/{역할}/{벤더}/`
  - 역할을 벤더보다 위에 둔다 — 같은 역할의 구현들이 한자리에 모여야 교체 후보가 보인다. 벤더 이름만으로는 무슨 연동인지 드러나지 않는다
  - 표기: 혼자 읽히는 이름은 풀네임(`PaymentGatewayClient`·`PAYMENT_GATEWAY_ERROR`·`infrastructure/paymentgateway/`), 다른 이름 앞에 붙는 접두는 축약(`PgConfirmResult`·`pgPaymentKey`). 접두에 풀네임을 쓰면 `PaymentGatewayPayment` 처럼 말더듬이 나고, 혼자 선 `Pg`/`pg` 는 PostgreSQL 로 읽힌다
- 변수: camelCase, 줄임말 지양. boolean `isXxx`, 컬렉션 `{타입}List`. 메서드 동사 시작.

## 코딩 컨벤션

### 들여쓰기 — Java 공백 2칸 고정

Java 코드는 **공백 2칸** 들여쓰기로 고정한다(탭 금지). 루트 `.editorconfig` 의 `[*.java] indent_size=2` 로 강제돼 IDE 가 자동 적용하며, 코드 생성·편집 시에도 2칸을 지킨다.

### 응답 — ApiResponse 자동 래핑

컨트롤러는 **raw DTO 를 반환**한다. 전역 `ApiResponseAdvice`(ResponseBodyAdvice)가 `{result, data, error}`(`ApiResponse`) 로 자동 래핑한다. **컨트롤러에서 직접 감싸지 않는다.** 실패는 `GlobalExceptionHandler` 가 `ApiResponse.fail(...)` 로 처리.
- 성공 `{"result":true,"data":{...}}` / 실패 `{"result":false,"error":{"code":"...","message":"..."}}`

### 예외 — DomainException + ExceptionCode enum

- `throw new DomainException({Domain}ExceptionCode.X)`. 도메인별 `enum implements ExceptionCode`(HttpStatus + message 직접 보유). 전역 공통은 `DomainExceptionCode`.
- `IllegalStateException`/`IllegalArgumentException` 등 커스텀 RuntimeException 남발 금지.

### 검증 — Bean Validation 우선

- 단순 입력 검증(필수/길이/형식/부호/날짜)은 record 필드의 jakarta.validation 어노테이션 → 400 `VALIDATION_ERROR`.
- 비즈니스 룰(상태 의존, Repository 조회)은 엔티티/서비스에서 `DomainException`.

### 공통 패턴

- 엔티티/DTO/QueryDSL 상세는 `.claude/rules/` + `.claude/skills/api-impl/`.
- 논리삭제는 도메인 선택(현재 사용 도메인 없음), 기본은 상태 enum(`ProductStatus` 등).
- 엔티티는 정적 팩토리 `create(Request, 연관)` + 도메인 메서드 캡슐화. Response 변환은 MapStruct.

### 주석 — self-documenting 우선 (주석 최소화)

- **기본 주석 0.** 의미는 메서드명·변수명·타입·구조로 드러낸다(좋은 이름 > 주석). "무엇을 하는지(what)" 설명하는 주석 금지 — **클래스/메서드 Javadoc(`/** */`)도, 테스트의 given/when/then 마커 주석도 금지**(이름·구조로 표현).
- **예외**: 로직이 본질적으로 복잡해 이름만으로 안 될 때만 **왜(why)** 를 한 줄로 최소 작성. what 반복 금지.
- 필드/파라미터 설명 주석 금지 — 이름으로 표현(`aggregateId`/`resultType` 등). 자명한 DDL·설정 항목도 주석 없이. 설정 파일(yaml/compose)은 꼭 필요한 섹션 표식만.
- **단계적 구현 중 덜 된 부분은 `// TODO(범위): {남은 일}`** 로 명시(임시 처리·미구현 seam 추적용. 예: `// TODO(outbox): 발행 폴러 미구현`). 완료 시 TODO 제거.
- **에이전트 생성 코드도 동일** — 설명 주석 제거, 필요한 TODO 만 유지.

## 코드 분석/편집 도구 정책

Serena MCP 가 등록돼 있어 **Java 소스의 심볼 단위 분석/편집은 Serena 우선** 사용.

| 작업 | 도구 |
|------|------|
| 심볼 위치 찾기 | `find_symbol` |
| 참조처 추적 | `find_referencing_symbols` |
| 심볼 단위 수정 | `replace_symbol_body`, `insert_after_symbol` |
| 파일 심볼 개요 | `get_symbols_overview` |
| 컴파일 진단 | `get_diagnostics_for_file` |

비-Java(YAML/properties/MD/SQL) 편집·빌드/git 은 기본 도구(Edit/Write/Bash).

## 빌드/실행 명령

- 컴파일 확인: `./gradlew compileJava`
- 전체 빌드/테스트: `./gradlew build` / `./gradlew test` (testcontainers — Docker 필요)
- 커버리지: `./gradlew test jacocoTestReport`

## 통합 테스트

상세는 [.claude/rules/integration-test.md](.claude/rules/integration-test.md). 핵심: **공통 베이스 클래스 없이** 각 테스트가 `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @WithMockUser` 를 직접 사용. testcontainers MySQL 8.4 + Redis. `@Nested/@DisplayName`(한글), `repository.deleteAll()` 격리, 단언은 ApiResponse 구조(`$.result`/`$.data`/`$.error.code`).

## Phase/Slice별 스킬 호출

각 단계 진입 시 적절한 스킬을 **Skill 도구로 직접 호출**한다.

| 단계 | 호출할 스킬 |
|------|-----------|
| Phase 0 (의도 추궁) | `api-design` |
| Phase 1 (설계 문서) | `api-design` |
| Phase 2 기반 (DTO/엔티티/마이그레이션) | `api-impl` 또는 직접 |
| Phase 2 vertical TDD 사이클 | mattpocock `tdd` |
| 디버깅 | mattpocock `diagnose` |
| 신규 기능 브레인스토밍 | `brainstorming` |
| 최종 검증 | `qa-validation` |

## 글로벌 스킬 (mattpocock)

`~/.claude/skills/` 의 핵심 개발 스킬을 활용한다:
- **tdd**: vertical-slice TDD (RED→GREEN→REFACTOR)
- **diagnose**: 같은 버그 재발을 막는 테스트 강제
- **brainstorming**: 기능/설계 전 요구·의도 탐색
- **improve-codebase-architecture**: 리팩토링 (Module/Depth/Seam)

산출물은 한국어로 작성.

## 출력 언어 룰

모든 산출물은 한국어로 작성. 예외(원문 유지): 코드 식별자(클래스/메서드/패키지명), 외부 문서 인용. 테스트 `@DisplayName` 은 한국어 시나리오, 메서드명은 영어 camelCase.

## 진행 상황 노출 + 커밋 체크포인트

상세는 [.claude/rules/commit-checkpoint.md](.claude/rules/commit-checkpoint.md) (globs 없이 항상 로드). 핵심:
- **자동 커밋 절대 금지** (사용자 직접 커밋). 작업 단위마다 멈춰 추천 커밋 메시지(한 줄 명령)를 제시 후 대기.
- vertical TDD 작업 시 진행 매트릭스(슬라이스/사이클) + TodoWrite 노출.
