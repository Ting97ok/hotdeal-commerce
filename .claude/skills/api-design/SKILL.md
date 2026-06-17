---
name: api-design
description: "새 도메인의 API 설계 문서를 작성하거나 리뷰할 때 사용. 기존 설계 문서 포맷과 엔티티 구조를 참조하여 admin/user별 설계 초안을 생성하고, 아키텍처 정합성을 검증"
argument-hint: "[도메인명 또는 설계문서 경로]"
allowed-tools: Read, Grep, Glob, Write, Edit, Agent, TodoWrite, AskUserQuestion
model: opus
---

# API 설계 워크플로우

대상 도메인: $ARGUMENTS

## 절대 규칙
- `.java`, `.xml`, `.sql`, `.yml`, `.yaml`, `.gradle` 등 소스 파일을 생성·수정하지 않는다.
- Write/Edit 는 `docs/{도메인}/` 하위 `.md` 파일에만 사용한다.
- 코드 구현/컴파일/빌드/테스트를 실행하지 않는다.
- 설계 문서 내 코드 블록은 구현 가이드용 **의사 코드**이며 실제 파일에 반영하지 않는다.

**결과물은 오직 `docs/{도메인}/` 하위 마크다운뿐이다.**

## Phase 0: 의도 추궁 (Grilling)

설계 문서 작성 전, 결정 트리를 한 번에 하나씩 `AskUserQuestion` 으로 묻는다. 답을 코드/기존 문서에서 확인 가능하면 묻지 말고 직접 탐색 후 "X로 추정했는데 맞나요?" 로 확인.

### 질문 형식
- 옵션 2~4개(mutually exclusive), 첫 옵션 라벨에 `(권장)` 표기.
- 각 옵션 description 에 선택 결과/트레이드오프 명시.
- 한 질문당 답변 받기 전 다음 질문 금지.

### 결정 트리 7개 카테고리 (commerce)

**A. 도메인 정의**
- A1. 이 도메인의 핵심 기능은? (1줄)
- A2. 기존 어떤 도메인과 가장 비슷한가? (product / coupon / cart / category / search ...)

**B. 권한 분리**
- B1. 호출자는? (admin only / user only / 양쪽 / 시스템 자동)
- B2. URL 그룹: 관리자 `/api/admin/...` vs 사용자 `/api/...`
- B3. 비회원 접근 허용? (`@CurrentUser` null 허용)

**C. 데이터 라이프사이클**
- C1. 삭제 정책: 논리삭제(`isDeleted`) / 하드 삭제 / 상태 enum(`STOP_SALE` 등)?
- C2. 만료/보관 정책이 있는가?
- C3. 변경 이력(createdAt/updatedAt 외 audit) 필요?

**D. 외부 의존**
- D1. 외부 API? (OpenAI/PgVector 임베딩, OpenFeign 외부 동기화, 결제 mock ...)
- D2. 다른 도메인 엔티티/서비스 참조? (cross-domain → **Facade 에서 타 도메인 Service 호출**)
- D3. 트랜잭션 경계: Facade 메서드별 지정 / Service 클래스 readOnly + 쓰기 오버라이드 / 외부 호출 분리

**E. CRUD 디테일**
- E1. 목록 조회 페이지네이션? 사이즈 기본값? (envelope 응답)
- E2. 검색/필터 조건? 동적 쿼리(QueryDSL) 필요?
- E3. 정렬 기준? (createdAt / price / 사용자 지정)

**F. 부가 기능**
- F1. Redis 캐싱? (cache-aside, 무효화 정책)
- F2. 변경 시 이벤트 발행/리스너? (캐시 무효화, 벡터 동기화 등)
- F3. 동시성 제어? (낙관적 락, 카운터)

**G. 특수 검증**
- G1. 형식 검증이 `@Pattern`/`@Email` 등 Bean Validation 으로 커버되는가?
- G2. 길이/범위 제한, 한국어 입력 처리?
- G3. 날짜/시간 포맷 표준?

### Phase 0 종료
7개 카테고리 답변이 확보(또는 "기존 X와 동일")되면 결정사항 요약을 제시 → 사용자 확인("OK"/"X 수정") 후 Phase 1. Phase 0 은 산출물(파일)이 없으므로 커밋 체크포인트 미발동.

## Phase 1: 설계 문서 초안

### 참조 수집
1. 기존 설계 문서 구조 파악 (`docs/week2~7-api-design*.md`). 신규는 `docs/{도메인}/`.
2. 유사 도메인 엔티티/enum/쿼리 패턴(Product / Coupon / Category).

### 문서 구조
1. **공통 정의 (`api-design.md`)**: 개요(배경/목적/제외), **변경 이력**, **알려진 제약**, 엔티티 필드 테이블, 공통 응답 형식(ApiResponse).
2. **역할별 상세 (`api-design-{role}.md`)**: 개요, API 목록 테이블, 개별 API 상세.

### API별 섹션 순서
1. **Endpoint** — `Method + URL` (관리자 `/api/admin/...`, 사용자 `/api/...`)
2. **설명** — blockquote
3. **Request** — 파라미터/타입/필수/Validation/설명 (record 필드 기준)
4. **검증** — 검증 항목 / 방식(Bean Validation vs 비즈니스) / 에러코드
5. **Response JSON 예시** — ApiResponse 구조(`{"result":true,"data":{...}}`)
6. **Response 필드** — 필드/타입/설명/매핑(MapStruct)
7. **테스트 리스트** — TDD 사이클로 채움. 헤더: `# / 테스트 케이스 / 시나리오 / 상태 / 작성일`. **설계 단계는 빈 표(헤더만)**, placeholder 행 금지.
8. **구현 로직** — Mermaid `flowchart TD`
9. **엔티티 메서드 설계** — 정적 팩토리 / 도메인 메서드(필요 시)
10. **쿼리 설계** — JPQL 또는 QueryDSL, 엔티티 객체 파라미터 우선

> commerce 는 컨트롤러에 Swagger 어노테이션(@Tag/@Operation/GroupedOpenApi)을 쓰지 않으므로 **Swagger 설정 섹션은 작성하지 않는다.**

### 검증 섹션 작성 기준
- **Bean Validation**(단순 입력): `@NotNull`/`@NotBlank`/`@Size`/`@Min`/`@DecimalMin`/`@Pattern`/`@Email`/`@Future` → 400 `VALIDATION_ERROR`.
- **비즈니스 검증**(상태 의존/Repository 조회): 엔티티/서비스에서 `throw new DomainException({Domain}ExceptionCode.X)` → enum 의 HttpStatus.

### Mermaid Flowchart 스타일
```
classDef error fill:#f8d7da,stroke:#dc3545,color:#dc3545,font-weight:bold
classDef success fill:#d4edda,stroke:#28a745,color:#155724
classDef process fill:#d1ecf1,stroke:#17a2b8,color:#0c5460
classDef decision fill:#fff3cd,stroke:#ffc107,color:#856404
```

### 설계 노트
의사결정 배경/주의는 blockquote: `> **설계 노트 — {주제}**: {내용}`. 시나리오 표현은 개발 용어보다 사람이 이해하기 쉬운 표현 우선(CLAUDE.md `## 표현 룰`).

### 타 문서 링크 (파일 레벨)
타 문서의 **특정 섹션·결정**을 참조할 때는 섹션 번호를 텍스트에 적고 링크는 파일 레벨로 건다(`[ADR-0007 결정3](../adr/0007-hotdeal-state-operations.md)`처럼 `#앵커` 없이). `<a id>`·자동 슬러그 앵커는 IntelliJ가 지원하지 않아 쓰지 않는다(파일 레벨 링크는 IntelliJ·GitHub 둘 다 작동). 상세 컨벤션은 [docs/README.md](../../../docs/README.md).

## Phase 2: 설계 문서 리뷰
1. 참조 엔티티/Repository 가 실제 코드에 존재하는지 확인.
2. 네이밍: Request `{Action}{Domain}Request`(record), Response record + MapStruct.
3. 쿼리: 엔티티 객체 파라미터 우선.
4. 아키텍처: 신규 4계층(Controller→Facade→Service→Repository, cross-domain 은 Facade 에서 타 도메인 Service 호출), 트랜잭션(Service readOnly + 쓰기 오버라이드, Facade 메서드별), 누락 예외/Validation.

## 결과물
- `docs/{도메인}/api-design.md` (공통 정의)
- `docs/{도메인}/api-design-admin.md` / `api-design-user.md` (역할별)

## Phase 종료 시 커밋 체크포인트
`.claude/rules/commit-checkpoint.md` 양식으로 작업 내역 + 추천 커밋 메시지를 제시하고 사용자 직접 커밋을 대기한다. **자동 커밋 금지**. Phase 1 종료 추천 접두사 `[Docs]`.

**출력 언어**: 한국어 (예외 — 코드 식별자, 외부 문서 인용은 원문 유지).
