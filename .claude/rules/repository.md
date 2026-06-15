---
globs: **/repository/*Repository.java
---

# Repository 규칙

commerce Repository 는 `domain/{도메인}/repository/` 에 위치한다.

## 기본 구조

- `extends JpaRepository<엔티티, Long>` + QueryDSL 동적 쿼리가 필요하면 `{Domain}RepositoryCustom` 도 함께 상속:
  ```java
  public interface ProductRepository extends JpaRepository<Product, Long>, ProductRepositoryCustom { ... }
  ```

## derived query vs @Query

- **단순 조건(1~2개)**: derived query (`existsByCategoryId`, `findByExternalProductId`).
- **fetch join / 복잡 조건 / 명시적 쿼리**: `@Query` JPQL — 가독성 위해 text block 권장:
  ```java
  @Query("""
      SELECT p FROM Product p
      LEFT JOIN FETCH p.options
      LEFT JOIN FETCH p.category
      WHERE p.id = :productId AND p.status = 'FOR_SALE'
  """)
  Optional<Product> findByIdWithOptionsAndCategory(@Param("productId") Long productId);
  ```
- 동적 조건/페이징/프로젝션은 QueryDSL (`{Domain}RepositoryCustomImpl` — [querydsl.md](querydsl.md) 참조).

## 관례

- `>= 1` 비교만 하는 카운팅은 `exists` 사용 (DB limit 1, 의미 직접적).
- 메서드명은 의미 중심 — 조건이 많으면 derived 조합 대신 `@Query` + 의미 명명.
- 상태/고정 분류(`status = 'FOR_SALE'`)는 쿼리 안에 박아 호출부 인자를 줄인다.
- 대량 upsert·성능 경로는 별도 JDBC 리포지토리(`ProductExternalJdbcRepository`, `CouponUserJdbcRepository`) — JdbcTemplate native.
