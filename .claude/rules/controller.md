---
globs: **/controller/**/*.java
---

# 컨트롤러 규칙

commerce 컨트롤러는 `domain/{도메인}/controller/` 에 위치한다.

## 선언 / 응답

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
public class ProductAdminController {

    private final ProductAdminService productAdminService;

    @PostMapping
    public CreateProductResponse createProduct(@RequestBody @Valid CreateProductRequest request) {
        return productAdminService.createProduct(request);     // raw DTO 반환
    }
}
```

- **응답은 raw DTO 를 그대로 반환**한다. 전역 `ApiResponseAdvice`(`ResponseBodyAdvice`) 가 자동으로 `ApiResponse`(`{result, data, error}`) 로 감싼다.
- **컨트롤러에서 `ApiResponse` 로 직접 감싸지 않는다** — 이중 래핑이 된다. 예외는 `GlobalExceptionHandler` 가 `ApiResponse.fail(...)` 로 처리.
- 직렬화 결과: 성공 `{"result":true,"data":{...}}` / 실패 `{"result":false,"error":{"code":"...","message":"..."}}`.

## URL / 파라미터

- 관리자 API: `/api/admin/{resource}`, 사용자 API: `/api/{resource}`.
- 쓰기: `@RequestBody @Valid {record}`. GET 검색: `@Valid {record}` + `@PageableDefault(...) Pageable`.
- 식별자: `@PathVariable Long {id}`.
- 페이지 조회는 envelope 반환 (`ProductPageEnvelope.from(page)`).

## 인증

- 인증 사용자 정보가 필요하면 `@CurrentUser CurrentUser currentUser` (SecurityContext 기반 ArgumentResolver, 비회원은 `null`).
- 권한은 Spring Security role 기반 (`SecurityConfig`, 테스트 `@WithMockUser(roles = "ADMIN")`).

## Swagger / 네이밍

- commerce 는 컨트롤러에 `@Tag`/`@Operation` 등 Swagger 어노테이션을 **사용하지 않는다**(springdoc 자동 문서화에 의존). 강제하지 않음.
- 클래스명은 `{Domain}{Role}Controller`(`ProductAdminController`, `CategoryAdminController`) 또는 `{Role}{Domain}Controller`(`AdminCouponController`) 가 혼재 — 도메인 내 일관성만 맞추면 됨.
