---
name: domain-context
description: "특정 도메인(상품, 쿠폰, 카테고리 등) 작업 시작 시 설계 문서와 기존 코드 구조를 한번에 파악"
argument-hint: "[도메인명 (예: product, coupon)]"
allowed-tools: Read, Grep, Glob
model: haiku
context: fork
---

# 도메인 컨텍스트 로딩

대상 도메인: $ARGUMENTS

## 수행 작업

1. **MEMORY.md 확인**: `~/.claude/projects/-Users-apartmant-Desktop-study-sparta-msa-project-final/memory/MEMORY.md` 에서 해당 도메인 진행 상황 확인
2. **설계 문서 확인**: `docs/{도메인}/api-design*.md` 탐색 (없으면 기존 `docs/week*-api-design*.md` 참고)
3. **엔티티 구조 파악**: `domain/{도메인}/entity/` 의 Entity, enum, `{Domain}ExceptionCode`
4. **서비스 계층 확인**: Controller → Service → Repository 구현 현황 (역할별/기능별 서비스 분포)
5. **DTO/Mapper 확인**: `dto/request`·`dto/response` record + `mapper`(MapStruct)
6. **진행 상황 요약**: 설계 대비 구현 완료/미완료 API 정리
