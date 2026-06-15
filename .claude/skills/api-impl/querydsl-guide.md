# QueryDSL 상세 가이드 (commerce)

## 기본 구조

```java
import static ...product.entity.QProduct.product;
import static ...category.entity.QCategory.category;

@RequiredArgsConstructor
public class ProductRepositoryCustomImpl implements ProductRepositoryCustom {
    private final JPAQueryFactory queryFactory;
}
```

- `JPAQueryFactory` 빈은 `global/config/QueryDslConfig` 가 제공. Q클래스는 static import.

## 동적 조건 — private BooleanExpression (null = 자동 생략)

```java
.where(
    product.status.eq(ProductStatus.FOR_SALE),
    keywordContains(request.keyword()),
    priceGoe(request.minPrice()),
    priceLoe(request.maxPrice()),
    categoryMatch(searchCategory)
)

private BooleanExpression keywordContains(String keyword) {
    return keyword != null ? product.name.containsIgnoreCase(keyword) : null;
}
private BooleanExpression priceGoe(BigDecimal minPrice) {
    return minPrice != null ? product.price.goe(minPrice) : null;
}
```

- `null` 반환 시 해당 조건은 `where` 에서 자동으로 빠진다.
- 소프트 삭제(useFlag) 필터 대신 **상태 필터**(`status.eq(FOR_SALE)`)를 도메인 정책에 맞게 사용.
- DB 벤더 전용 함수 금지(Hibernate 6 호환).

## 프로젝션 — @QueryProjection record

DTO 를 직접 select 하려면 record 생성자에 `@QueryProjection` 을 달아 Q타입 사용:

```java
List<ProductListResponse> content = queryFactory
    .select(new QProductListResponse(
        product.id, product.name, product.price, product.stock,
        product.isOrderable, product.externalProductId))
    .from(product)
    .join(product.category, category)
    .where(...)
    .orderBy(getOrderSpecifier(pageable))
    .offset(pageable.getOffset())
    .limit(pageable.getPageSize())
    .fetch();
```

## 페이징 — PageableExecutionUtils

```java
JPAQuery<Long> countQuery = queryFactory
    .select(product.count())
    .from(product)
    .join(product.category, category)
    .where(/* content 와 동일 조건 */);

return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
```

- `countQuery::fetchOne` 은 메서드 참조(지연 실행) — content 가 페이지 크기보다 작으면 카운트 쿼리 생략.
- content 쿼리와 count 쿼리의 `where` 조건은 동일해야 한다.
- `offset`/`limit`/`orderBy` 는 content 쿼리에만.

## 정렬 — Pageable sort 매핑

```java
private OrderSpecifier<?> getOrderSpecifier(Pageable pageable) {
    if (pageable.getSort().isEmpty()) {
        return product.createdAt.desc();              // 기본 정렬
    }
    Sort.Order order = pageable.getSort().iterator().next();
    return switch (order.getProperty()) {
        case "price" -> order.isAscending() ? product.price.asc() : product.price.desc();
        case "createdAt" -> order.isAscending() ? product.createdAt.asc() : product.createdAt.desc();
        default -> product.createdAt.desc();
    };
}
```
