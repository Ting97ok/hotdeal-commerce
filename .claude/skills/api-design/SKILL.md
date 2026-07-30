---
name: api-design
description: 새 도메인이나 API 의 설계 문서를 `docs/{도메인}/` 에 작성하거나 리뷰할 때 사용. 이 저장소의 설계 문서 섹션 순서와 작성 기준을 담는다. '설계 문서 써줘', '{도메인} API 설계', '설계만 해줘', '이 설계 리뷰해줘' 같은 요청에서 사용.
argument-hint: "[도메인명 또는 설계문서 경로]"
allowed-tools: Read, Grep, Glob, Write, Edit, AskUserQuestion
---

# API 설계 문서

대상: $ARGUMENTS

**산출물은 `docs/{도메인}/` 하위 마크다운뿐이다.** 소스 파일(`.java`·`.sql`·`.gradle`·`.yaml`)을 만들거나 고치지 않고, 빌드·테스트를 돌리지 않는다. 문서 안의 코드 블록은 구현 가이드용 의사 코드다.

## 먼저 물을 것

코드나 기존 문서에서 확인되는 것은 묻지 말고 직접 확인한 뒤 "X 로 봤는데 맞나요?"로 짚는다. 아래는 **이 저장소에서 답이 갈리고 코드로는 알 수 없는 것들**이다.

1. **호출자와 URL 그룹** — 관리자 `/api/admin/...` / 사용자 `/api/...` / 양쪽 / 시스템 자동. 비회원 접근(`@CurrentUser` null) 허용 여부
2. **삭제·종료 정책** — 상태 enum / 논리삭제(`isDeleted`) / 하드 삭제. 이 저장소는 대개 상태 enum 을 쓴다
3. **동시성** — 재고·수량처럼 경합하는 자원을 건드리는가. 그렇다면 [동시성 ADR](../../../docs/adr/concurrency.md) 의 기존 전략을 따르는가
4. **도메인 경계** — 타 도메인 데이터가 필요한가. 필요하면 Facade 에서 그 도메인 Service 를 부르는 구조가 되는가
5. **트랜잭션 경계** — 외부 호출(PG 등)이 끼는가. 끼면 트랜잭션 밖으로 뺄 지점은 어디인가

요구사항 자체가 흐릿하면 전역 `brainstorming`·`grill-me` 스킬로 먼저 의도를 파고, 확정된 뒤 여기로 돌아온다.

답이 모이면 결정사항을 요약해 사용자 확인을 받고 문서 작성으로 넘어간다.

## 문서 구조

| 파일 | 담는 것 |
|---|---|
| `docs/{도메인}/api-design.md` | 개요(배경·목적·제외), 변경 이력, 알려진 제약, 엔티티 필드 표 |
| `docs/{도메인}/api-design-{role}.md` | 역할별 개요, API 목록 표, API 상세 |

## API 상세 — 섹션 순서

1. **Endpoint** — `Method + URL`
2. **설명** — blockquote 한 줄
3. **Request** — 파라미터 / 타입 / 필수 / Validation / 설명 (record 필드 기준)
4. **검증** — 항목 / 방식 / 에러코드
5. **Response JSON 예시** — 래핑된 구조(`{"result":true,"data":{...}}`)
6. **Response 필드** — 필드 / 타입 / 설명 / 매핑
7. **테스트 리스트** — `# / 테스트 케이스 / 시나리오 / 상태 / 작성일`. **설계 단계에서는 헤더만 있는 빈 표.** placeholder 행을 넣지 않고 TDD 사이클마다 한 줄씩 쌓는다
8. **구현 로직** — Mermaid `flowchart TD`
9. **엔티티 메서드** — 정적 팩토리·도메인 메서드 (필요할 때만)
10. **쿼리 설계** — JPQL 또는 QueryDSL

검증 섹션은 둘로 나눠 적는다. **단순 입력**은 Bean Validation → 400 `VALIDATION_ERROR`. **상태 의존·조회가 필요한 룰**은 `DomainException({Domain}ExceptionCode.X)` → enum 의 HttpStatus.

Swagger 어노테이션을 쓰지 않으므로 Swagger 설정 섹션을 만들지 않는다.

### Mermaid 색상

```
classDef error fill:#f8d7da,stroke:#dc3545,color:#dc3545,font-weight:bold
classDef success fill:#d4edda,stroke:#28a745,color:#155724
classDef process fill:#d1ecf1,stroke:#17a2b8,color:#0c5460
classDef decision fill:#fff3cd,stroke:#ffc107,color:#856404
```

### 설계 노트

의사결정 배경과 주의는 blockquote 로 — `> **설계 노트 — {주제}**: {내용}`.

**문서 작성 규약(링크 방식·용어·문서 종류별 역할)은 [docs/README.md](../../../docs/README.md) 가 정본이다.** 여기에 옮겨 적지 않는다 — 그 기준은 계속 다듬어지고 있어서 사본을 두면 갈라진다.

## 리뷰할 때

1. 참조한 엔티티·Repository 가 실제 코드에 있는가
2. 네이밍 — Request `{Action}{Domain}Request`, Response record + MapStruct
3. 계층 — Controller→Facade→Service→Repository. cross-domain 은 Facade 에서
4. 트랜잭션 — Service 는 클래스 readOnly + 쓰기 오버라이드, Facade 는 메서드별
5. 누락된 예외·검증

## 끝나면

작업 내역과 추천 커밋 명령을 내고 사용자 커밋을 기다린다(접두사 `[Docs]`). 절차는 전역 `~/.claude/CLAUDE.md`.
