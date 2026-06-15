---
globs: **/repository/*Impl.java, **/repository/*Custom.java
---

# QueryDSL 규칙

commerce QueryDSL 구현은 `domain/{도메인}/repository/{Domain}RepositoryCustomImpl` 에 둔다.

## 기본 구조

```java
import static ...product.entity.QProduct.product;
import static ...category.entity.QCategory.category;

@RequiredArgsConstructor
public class ProductRepositoryCustomImpl implements ProductRepositoryCustom {
    private final JPAQueryFactory queryFactory;
}
```

- `JPAQueryFactory` 는 `QueryDslConfig` 빈 주입. Q클래스는 static import.

## 동적 조건 — private BooleanExpression (null = 생략)

조건 메서드를 private `BooleanExpression` 으로 분리하고, 값이 없으면 `null` 을 반환해 `where` 에서 자동 생략:
```java
.where(
    product.status.eq(ProductStatus.FOR_SALE),
    keywordContains(request.keyword()),
    priceGoe(request.minPrice()),
    categoryMatch(searchCategory)
)
private BooleanExpression keywordContains(String keyword) {
    return keyword != null ? product.name.containsIgnoreCase(keyword) : null;
}
```

- 소프트 삭제 필터(useFlag) 대신 **상태 필터**(`status.eq(FOR_SALE)`)를 도메인 정책에 맞게 사용.
- DB 벤더 전용 함수 금지 (Hibernate 6 호환).

## 프로젝션 / 페이징

- DTO 직접 select 는 `@QueryProjection` 생성자 record 의 Q타입(`new QProductListResponse(product.id, product.name, ...)`).
- 카운트 쿼리는 별도로 만들고 `PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne)` — 메서드 참조(지연 실행). content/count 의 `where` 조건은 동일하게.
- 정렬은 `Pageable` 의 sort 를 `OrderSpecifier` 로 매핑(`getOrderSpecifier(pageable)`), 기본 정렬 명시(`product.createdAt.desc()`).
