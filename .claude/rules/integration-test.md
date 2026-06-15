---
globs: **/*Test.java, **/src/test/**/*.java, **/application-test.yaml
---

# 통합 테스트 규칙

commerce 통합 테스트는 **공통 베이스 클래스 없이** 각 테스트가 직접 어노테이션을 단다 (testcontainers MySQL 8.4 + Redis 기반).

## 클래스 선언

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")          // 인증 필요 API
@DisplayName("카테고리 등록 API")
class CreateCategoryIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CategoryRepository categoryRepository;
}
```

- 인프라: testcontainers MySQL 8.4(`application-test.yaml` 의 `jdbc:tc:mysql:8.4:///test`) + Redis(`RedisTestcontainerConfig` — testcontainers `redis:7` + `@ServiceConnection`). 운영과 동일하게 Flyway 마이그레이션(V1~)이 그대로 실행되므로 MySQL 방언 DDL 을 테스트에서도 검증한다(`ddl-auto: none`).
- 인증: `@WithMockUser(roles = "...")` (JWT 직접 생성하지 않음). 공개 API(회원가입·로그인 등)는 어노테이션 생략.

## 구조 / 격리

- `@Nested @DisplayName`(한글)으로 성공/실패 그룹화, `@Test @DisplayName`(한글 시나리오), 메서드명은 영어 camelCase(`createRootCategory`).
- 격리: `@BeforeEach` 에서 `repository.deleteAll()`. 클래스 레벨 `@Transactional` 을 붙이지 않음 — 각 요청이 독립 트랜잭션이라 `mockMvc.perform` 직후 repository 조회로 검증 가능.

## 단언 — ApiResponse 구조

```java
mockMvc.perform(post("/api/admin/categories").contentType(APPLICATION_JSON).content(...))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.result").value(true))
    .andExpect(jsonPath("$.data.categoryId").isNumber());
```

- 성공: `$.result == true`, `$.data.{필드}`.
- 실패: `$.result == false`, `$.error.code == "{ExceptionCode 이름}"` (예: `"CATEGORY_NOT_FOUND"`).
- Bean Validation 실패 → 400, 비즈니스 검증 실패 → `DomainException` 의 HttpStatus(404/400/409 등).
- 외부 시스템(외부 PG·API 등)은 `@MockBean` 또는 가짜 대역(test double)으로 차단.
