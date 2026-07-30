---
name: commerce-dev-flow
description: "commerce 프로젝트의 신규 도메인 설계, 엔티티/마이그레이션 작성, 구현 완료 후 리뷰·검증을 6명 에이전트(domain-analyst, backend-architect, api-designer, api-implementer, code-reviewer, qa-validator)로 자동 조율. Repository/Service/Controller 구현은 vertical TDD(tdd 스킬)로 진행 — 본 스킬은 TDD 진입 전(분석·설계·엔티티/마이그레이션)과 TDD 종료 후(리뷰·검증)를 담당. '상품 옵션 설계해줘', '쿠폰 도메인 설계', '엔티티 만들어줘', '영향도 분석', '설계만 해줘', '구현 끝났으니 검증해줘', '리뷰만 다시', '재실행' 등 처리."
argument-hint: "[작업유형: 신규개발|기존수정|설계만] [도메인명]"
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent, TodoWrite, AskUserQuestion
model: opus
---

# commerce 개발 워크플로우 오케스트레이터

도메인 분석 → 설계 → 엔티티/마이그레이션까지 조율하고, **Repository 이하 구현은 vertical TDD(`tdd` 스킬)로 인계**한다. 구현 완료 후 리뷰 → 검증을 다시 조율한다. 각 단계 산출물은 `_workspace/` 에 저장되어 후속 에이전트의 입력이 된다.

> **TDD 표준**: commerce 도 정통 vertical-slice TDD 를 따른다. 본 스킬은 "TDD 진입 전 기반 마련(분석·설계·엔티티/마이그레이션)"과 "TDD 종료 후 검증(리뷰·QA)"만 담당. Repository/ExceptionCode/Service/Controller 자동 구현은 하지 않는다.

## 절대 규칙
- **모든 Agent 호출에 `model: "opus"` 명시**
- **`_workspace/` 디렉토리 보존** (감사용, 삭제 금지)
- 이전 산출물 존재 시 Phase 0(컨텍스트 확인)부터 시작
- 사용자 인계 전 qa-validator 통과 필수

## 작업 유형 분기
| 유형 | 트리거 | 실행 Phase |
|---|---|---|
| **신규개발** | "도메인 설계", "신규 도메인", "{도메인} 만들어" | 0 → 1 → 2 → 3(엔티티/마이그레이션) → **TDD 인계** |
| **기존수정** | "수정", "변경", "버그 수정" | 0 → 1(영향도) → TDD 인계/직접 |
| **설계만** | "설계만", "설계 초안" | 0 → 1 → 2 |
| **검증** | "구현 끝났으니 검증", "리뷰만 다시", "QA" | 4 → 5 |

모호하면 `AskUserQuestion` 으로 확인.

## Phase 0: 컨텍스트 확인
`_workspace/` 상태를 점검해 초기/부분 재실행/재개 모드를 결정. 판별 결과를 1-2줄 보고 후 진행. (미존재 → 생성 후 Phase 1)

## Phase 1: 도메인 분석
```
Agent(subagent_type="general-purpose", model="opus", description="domain-analyst",
  prompt=`
    당신은 .claude/agents/domain-analyst.md 에 정의된 commerce 도메인 분석가다.
    그 정의 파일을 먼저 읽고 절차/출력 프로토콜을 따른다.
    입력: 도메인명={도메인}, 작업유형={유형}
    출력: _workspace/01_domain_context.md 작성 + 핵심 발견 3줄
    활용 스킬: .claude/skills/domain-context/SKILL.md
  `)
```

## Phase 2: 설계 (신규개발 / 설계만)
api-designer 주도, 필요 시 backend-architect 자문:
```
Agent(subagent_type="general-purpose", model="opus", description="api-designer",
  prompt=`
    .claude/agents/api-designer.md 정의를 따른다.
    입력: _workspace/01_domain_context.md
    출력: docs/{도메인}/api-design.md, api-design-{role}.md, _workspace/02_design_summary.md
    활용 스킬: .claude/skills/api-design/SKILL.md
    자문 필요 항목은 _workspace/_questions_to_architect.md 에 기록.
  `)
```
`_questions_to_architect.md` 가 생기면 backend-architect(`.claude/agents/backend-architect.md`) 호출 → `_workspace/02_architect_review.md`. 차단 사안 있으면 api-designer 재호출. ("설계만" 이면 여기서 종료)

## Phase 3: 엔티티 + 마이그레이션 (신규개발)
> **범위 제한**: 엔티티 + 마이그레이션 SQL까지만. Repository 이하는 vertical TDD 대상.
```
Agent(subagent_type="general-purpose", model="opus", description="api-implementer",
  prompt=`
    .claude/agents/api-implementer.md 정의를 따라 엔티티 + 마이그레이션 SQL 작성.
    입력: _workspace/02_design_summary.md, docs/{도메인}/, _workspace/02_architect_review.md(있으면)
    규칙: .claude/rules/entity.md
    범위: 엔티티 매핑 + src/main/resources/db/migration/V{n}__*.sql 만.
    ./gradlew compileJava 통과 확인. 출력: _workspace/03_entity_summary.md
  `)
```
컴파일 2회 실패 시 backend-architect 자문 후 재시도.

### TDD 인계 (Phase 3 종료 후 — 멈춘다)
```
✅ {도메인} 설계 + 엔티티/마이그레이션 완료
다음: Repository/Service/Controller 는 `tdd` 스킬로 슬라이스별 vertical TDD 진행하세요.
       (각 API = 한 슬라이스, RED→GREEN→Docs 사이클)
구현이 끝나면 "검증해줘" 로 Phase 4(리뷰) → 5(QA) 를 호출하세요.
```

## Phase 4: 리뷰 (구현 완료 후)
```
Agent(subagent_type="general-purpose", model="opus", description="code-reviewer",
  prompt=`
    .claude/agents/code-reviewer.md 정의를 따라 git diff 변경 파일을 리뷰.
    설계 비교: _workspace/02_design_summary.md(있으면)
    Critical/Warning/Suggestion 분류. 출력: _workspace/04_review_result.md
  `)
```
**Critical 발견 시**: 사용자 보고 + 해당 슬라이스를 `tdd`/`diagnose` 로 수정 요청(구현은 TDD 영역). 수정 후 Phase 4 재실행. **Warning 만**: Phase 5 진행.

## Phase 5: 최종 검증
```
Agent(subagent_type="general-purpose", model="opus", description="qa-validator",
  prompt=`
    .claude/agents/qa-validator.md 정의를 따라 최종 검증.
    활용 스킬: .claude/skills/qa-validation/SKILL.md
    입력: _workspace/02_design_summary.md, _workspace/04_review_result.md, 도메인={도메인}
    6단계 검증 후 _workspace/05_qa_report.md 작성. 판정: 통과/경고/차단
  `)
```
**통과**: 사용자 인계(변경 파일 + 보고서 위치). **경고**: 통과 보고 + 개선 항목. **차단**: 리뷰 지적은 Phase 4 재실행 / 구현 결함은 `tdd`/`diagnose`.

## 데이터 전달
| 산출물 | 위치 | 작성자 |
|---|---|---|
| 도메인 분석 | `_workspace/01_domain_context.md` | domain-analyst |
| 설계 요약 | `_workspace/02_design_summary.md` | api-designer |
| 자문 결과 | `_workspace/02_architect_review.md` | backend-architect |
| 엔티티 요약 | `_workspace/03_entity_summary.md` | api-implementer |
| 리뷰 결과 | `_workspace/04_review_result.md` | code-reviewer |
| QA 보고서 | `_workspace/05_qa_report.md` | qa-validator |

회차별 보존 시 `_v{n}` 접미사.

## 에러 핸들링
- 에이전트 호출 실패 1회 재시도. 엔티티 컴파일 2회 실패→backend-architect 자문. Critical 리뷰 회차 3회 제한. QA 차단 2회 제한. 도메인 모호 시 AskUserQuestion 1회.

## 커밋 체크포인트
각 Phase 종료 시 `.claude/rules/commit-checkpoint.md` 양식으로 작업 내역 + 추천 커밋 메시지를 제시하고 사용자 직접 커밋을 대기한다. **자동 커밋 절대 금지**.
