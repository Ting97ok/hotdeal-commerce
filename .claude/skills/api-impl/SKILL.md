---
name: api-impl
description: "설계 문서 기반으로 API를 구현할 때 사용. 구현 순서, 상세 코딩 패턴, QueryDSL/엔티티/DTO 예시를 포함"
argument-hint: "[설계문서 또는 API명]"
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent, TodoWrite
---

# API 구현 가이드

대상: $ARGUMENTS

## 구현 순서
constant(Enum) → entity → repository → exception(`{Domain}ExceptionCode`) → service → facade → controller/dto → mapper(MapStruct)

> 신규 도메인은 **4계층 — Facade 전면**(단순 CRUD 도 Facade 경유). 기존 3계층 도메인 수정은 기존 패턴 유지.

## 구현 전 준비
1. 설계 문서 확인 (`docs/{도메인}/`)
2. 유사 도메인(Product/Category/Coupon)의 구현 패턴 참조
3. 공통 기반 확인 — `global/entity/BaseEntity`, `global/response/ApiResponse`(+`ApiResponseAdvice` 자동 래핑), `global/exception/{ExceptionCode, DomainException}`

## 구현 완료 후 검증
- `./gradlew compileJava` 성공 확인
- 설계 문서의 검증/예외처리 대비 구현 누락 점검

## 상세 코딩 패턴
- 엔티티/DTO 상세: [entity-dto-patterns.md](entity-dto-patterns.md)
- QueryDSL 상세: [querydsl-guide.md](querydsl-guide.md)
