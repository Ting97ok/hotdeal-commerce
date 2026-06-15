# 엔티티/DTO 상세 패턴 (commerce)

## 엔티티

```java
@Entity
@Getter
@Builder(access = PRIVATE)
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
@FieldDefaults(level = PRIVATE)
@DynamicInsert
@DynamicUpdate
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(nullable = false, length = 100)
    String name;

    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal price;

    @Enumerated(STRING)
    @Column(nullable = false, length = 20)
    ProductStatus status;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "category_id")        // FK 제약 사용 (NO_CONSTRAINT 아님)
    Category category;

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = LAZY)
    List<ProductOption> options = new ArrayList<>();

    // 정적 팩토리: Request 를 받고 외부 연관만 별도 파라미터, 검증·자식 생성을 캡슐화
    public static Product create(CreateProductRequest request, Category category) {
        Product product = Product.builder()
            .name(request.name())
            .price(request.price())
            .status(ProductStatus.FOR_SALE)
            .category(category)
            .isOrderable(request.stock() != null && request.stock() > 0)
            .build();
        product.validateDuplicateOptionNames(...);
        product.createOptions(request.options());
        return product;
    }

    // 도메인 검증/상태 변경 캡슐화 — 예외는 DomainException + ExceptionCode enum (static import)
    private void validateDuplicateOptionNames(List<String> names) {
        if (names.size() != Set.copyOf(names).size()) {
            throw new DomainException(DUPLICATE_OPTION_NAME);
        }
    }
}
```

## Request DTO (record + Bean Validation)

```java
public record CreateProductRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotNull @DecimalMin("0") BigDecimal price,
    @NotNull @Min(0) Integer stock,
    @NotNull Long categoryId,
    @Valid List<ProductOption> options
) {
    public CreateProductRequest {
        options = options != null ? options : List.of();    // compact constructor 기본값
    }
    public record ProductOption(
        @NotBlank String name,
        @NotNull @DecimalMin("0") BigDecimal additionalPrice,
        @NotNull @Min(0) Integer stock
    ) {}
}
```

## Response DTO (record) + MapStruct

```java
public record CreateProductResponse(Long productId) {}

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mapping(source = "id", target = "productId")
    CreateProductResponse toCreateResponse(Product product);
    ProductDetailResponse toDetailResponse(Product product);
    ProductDetailResponse.OptionInfo toOptionInfo(ProductOption option);
}
```

## 예외 코드 (ExceptionCode enum)

```java
@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum ProductExceptionCode implements ExceptionCode {

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    DUPLICATE_OPTION_NAME(HttpStatus.BAD_REQUEST, "같은 상품 내 동일한 옵션명이 존재합니다."),
    ;
    final HttpStatus status;
    final String message;
}
// 사용: throw new DomainException(ProductExceptionCode.PRODUCT_NOT_FOUND);
```

## 컨트롤러 (raw DTO 반환 — 자동 래핑)

```java
@PostMapping
public CreateProductResponse createProduct(@RequestBody @Valid CreateProductRequest request) {
    return productAdminService.createProduct(request);   // ApiResponseAdvice 가 {result,data} 로 래핑
}
```

> 컨트롤러에서 `ApiResponse` 로 직접 감싸지 않는다 (이중 래핑).

## 파사드 / 서비스 (신규 4계층)

```java
// Facade — cross-domain 조정 + Response 조립 (클래스 레벨 @Transactional 없음)
@Service
@RequiredArgsConstructor
public class ProductAdminFacade {
    private final ProductAdminService productAdminService;
    private final CategoryService categoryService;       // 타 도메인 Service 호출 OK
    private final ProductMapper productMapper;

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        Category category = categoryService.getCategory(request.categoryId());
        Product product = productAdminService.create(request, category);
        return productMapper.toCreateResponse(product);
    }
}

// Service — 자기 도메인 Repository + 도메인 로직, 엔티티 반환
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductAdminService {
    private final ProductRepository productRepository;

    @Transactional
    public Product create(CreateProductRequest request, Category category) {
        Product product = Product.create(request, category);
        return productRepository.save(product);
    }
}
```

> Controller 는 Facade 만 호출한다. 기존 3계층 도메인은 Service 가 직접 cross-domain Service 를 호출(유지).

## 페이지네이션 응답 (envelope)

```java
@GetMapping
public ProductPageEnvelope searchProducts(@Valid ProductSearchRequest request,
        @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    Page<ProductListResponse> page = productUserService.searchProducts(request, pageable);
    return ProductPageEnvelope.from(page);
}
```
