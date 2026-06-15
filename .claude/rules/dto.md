---
globs: **/dto/**/*.java
---

# DTO 규칙

commerce DTO 는 `domain/{도메인}/dto/request/`, `dto/response/` 에 위치하고 **record** 로 작성한다.

## Request DTO (record + Bean Validation)

- record 로 선언하고 필드에 jakarta.validation 어노테이션을 직접 부착:
  ```java
  public record CreateProductRequest(
      @NotBlank @Size(max = 100) String name,
      String description,
      @NotNull @DecimalMin("0") BigDecimal price,
      @NotNull @Min(0) Integer stock,
      @NotNull Long categoryId,
      @Valid List<ProductOption> options
  ) {
      public CreateProductRequest {                  // compact constructor 로 기본값
          options = options != null ? options : List.of();
      }
      public record ProductOption(                    // 중첩 record
          @NotBlank String name,
          @NotNull @DecimalMin("0") BigDecimal additionalPrice,
          @NotNull @Min(0) Integer stock
      ) {}
  }
  ```
- 단순 입력 검증은 **Bean Validation 우선** (`@NotNull`/`@NotBlank`/`@Size`/`@Min`/`@DecimalMin`/`@Pattern`/`@Email`/`@Future` ...). `GlobalExceptionHandler` 가 400 `VALIDATION_ERROR` 로 응답.
- 중첩 컬렉션은 `@Valid List<중첩record>`.
- 비즈니스 룰 검증(상태 의존, Repository 조회 필요)은 DTO 가 아니라 엔티티/서비스에서 `DomainException` 으로.

## Response DTO (record + MapStruct)

- Response 는 단순 record: `public record CreateProductResponse(Long productId) {}`.
- 엔티티 → Response 변환은 **MapStruct** 매퍼(`domain/{도메인}/mapper/`):
  ```java
  @Mapper(componentModel = "spring")
  public interface ProductMapper {
      @Mapping(source = "id", target = "productId")
      CreateProductResponse toCreateResponse(Product product);
      ProductDetailResponse toDetailResponse(Product product);
      ProductDetailResponse.OptionInfo toOptionInfo(ProductOption option);
  }
  ```
- 중첩 Response 는 record 안 inner record (`ProductDetailResponse.OptionInfo`).
- QueryDSL 프로젝션 대상 Response 는 `@QueryProjection` 생성자 record (`QProductListResponse`).
- 페이지 응답은 별도 envelope record (`ProductPageEnvelope.from(Page<...>)`).

## 네이밍

- Request: `{Action}{Domain}Request` (`CreateProductRequest`, `UpdateProductRequest`).
- Response: `{Action}{Domain}Response` 또는 `{Domain}{용도}Response` (`CreateProductResponse`, `ProductDetailResponse`, `CouponListItemResponse`) — 용도 중심, 혼재 허용.
