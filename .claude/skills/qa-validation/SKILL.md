---
name: qa-validation
description: "commerce 프로젝트 코드 구현 후 빌드 통과, 설계-구현 정합성, 계층 위반(Controller→Repository / Service→타도메인 Repository), ExceptionCode 일관성, ApiResponse 직접 래핑, 트랜잭션 어노테이션 누락 등 경계면 버그를 자동 탐지. 코드 변경 후 또는 PR 직전, 워크플로우의 최종 게이트로 사용."
argument-hint: "[도메인명]"
allowed-tools: Read, Grep, Glob, Bash
model: opus
---

# commerce QA 검증 스킬

빌드 통과만으로는 잡히지 않는 경계면 버그를 6단계로 탐지한다. 컴파일·정합성·계층·예외 일관성·응답/패턴·트랜잭션.

## 사용 시점
- 신규 도메인 API 구현 완료 후 / 기존 코드 수정 후 PR 직전 / code-reviewer 리뷰 통과 후 최종 게이트

## 입력 / 출력
- 입력: `_workspace/02_design_summary.md`(있으면) + 도메인명 인자. 없으면 `git diff --name-only` 로 변경 범위 추출.
- 출력: `_workspace/04_qa_report.md`

## 검증 6단계

### Step 1: 빌드
```bash
./gradlew compileJava
```
실패 시 즉시 보고 후 종료(다른 단계 무의미).

### Step 2: 설계 ↔ 구현 정합성
설계 문서(`docs/{도메인}/api-design-{role}.md`)와 코드를 shape 비교: Request record 필드/검증, Response record + MapStruct, 쿼리 조건, `{Domain}ExceptionCode`. 타입·이름·Optional 까지(단순 존재 확인 아님).

### Step 3: 계층 위반 (신규 4계층)
```bash
grep -rnE "(Service|Repository) [a-z][a-zA-Z]*(Service|Repository);" src/main/java/com/sparta/msa/commerce/domain/*/controller/ 2>/dev/null
grep -rn "Repository [a-z][a-zA-Z]*Repository;" src/main/java/com/sparta/msa/commerce/domain/*/facade/ 2>/dev/null
grep -rn "import com.sparta.msa.commerce.domain.*\.repository\." src/main/java/com/sparta/msa/commerce/domain/*/service/ 2>/dev/null
```
> 신규 도메인은 4계층(Controller→Facade→Service→Repository). 기존 3계층 도메인은 유지.

### Step 4: ExceptionCode 일관성
```bash
grep -rn "implements ExceptionCode" src/main/java/com/sparta/msa/commerce/domain/*/exception/ 2>/dev/null
grep -rn "throw new IllegalStateException\|throw new IllegalArgumentException" src/main/java/com/sparta/msa/commerce/domain/ 2>/dev/null
```

### Step 5: 응답 / 공통 패턴
```bash
grep -rn "ApiResponse" src/main/java/com/sparta/msa/commerce/domain/*/controller/ 2>/dev/null
grep -rln "Page<" src/main/java/com/sparta/msa/commerce/domain/*/repository/*Impl.java 2>/dev/null | xargs grep -L "PageableExecutionUtils" 2>/dev/null
```

### Step 6: 트랜잭션
```bash
find src/main/java/com/sparta/msa/commerce/domain -path "*/service/*Service.java" -exec sh -c '
  grep -B3 "public class.*Service" "$1" | grep -q "@Transactional(readOnly = true)" || echo "[확인] $1"
' _ {} \;
```

## 최종 판정
✅ 통과(Critical 0) / ⚠️ 경고 / ❌ 차단. 이전 보고서 있으면 회차별 비교·보존(`_v{n}`).

## 자주 놓치는 케이스 (commerce)
1. 컨트롤러가 `ApiResponse` 로 직접 래핑 — 전역 `ApiResponseAdvice` 와 이중 래핑
2. 도메인 ExceptionCode 가 `ExceptionCode` 인터페이스 미구현 / `IllegalState`·`IllegalArgument` 남발
3. Service 클래스 레벨 `@Transactional(readOnly = true)` 누락
4. Page 반환인데 `PageableExecutionUtils` 미사용(요청마다 카운트 쿼리)
5. record DTO 에 Bean Validation 누락
6. cross-domain 시 타 도메인 Repository 직접 주입(Service 경유해야)

## 작업 원칙
shape 비교(존재 확인 아님), 자동화 우선, **수정 금지**(탐지+보고만 — 구현은 `tdd`/`diagnose`, 엔티티는 api-implementer).
