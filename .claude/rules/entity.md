---
globs: **/entity/*.java
---

# 엔티티 규칙

commerce 도메인 엔티티는 `domain/{도메인}/entity/` 에 위치한다. 참조 구현: `Product`, `Category`, `Coupon`.

## 클래스 선언

- `BaseEntity` 상속 필수 — `id`(IDENTITY Long), `createdAt`(@CreationTimestamp), `updatedAt`(@UpdateTimestamp). 별도 식별자(uuid/seq) 없음.
- 표준 어노테이션 세트:
  ```java
  @Entity
  @Getter
  @Builder(access = PRIVATE)
  @NoArgsConstructor
  @AllArgsConstructor(access = PRIVATE)
  @FieldDefaults(level = PRIVATE)
  @DynamicInsert
  @DynamicUpdate
  @Table(name = "products")          // 테이블명: 복수형 스네이크 (셀 수 없는 명사는 단수 — 예: stock)
  public class Product extends BaseEntity { ... }
  ```
- 필드는 접근제어자 생략(`@FieldDefaults(level = PRIVATE)`), `@Column` 으로 제약 명시(nullable/length/precision/columnDefinition).
- 생성자·빌더는 `PRIVATE` — 외부 인스턴스화는 정적 팩토리로만.
- **필드 순서**: 값/스칼라·enum 칼럼을 먼저 선언하고, **JPA 연관 매핑(`@ManyToOne`/`@OneToMany` 등)은 클래스 하단**에 모은다(스키마·가독성 일관).

## 한국어 의미 주석 (@Comment / enum)

- **엔티티 칼럼**: 도메인 의미를 Hibernate `@Comment("한국어")` 로 필드 위에 명시한다. `ddl-auto: none` 이라 `@Comment` 자체는 DB 에 반영되지 않으므로, **Flyway DDL 칼럼의 `COMMENT '...'` 와 같은 문구로 일치**시킨다(엔티티=코드 가독성, DDL=실제 DB 메타).
- **enum 값**: 각 상수의 도메인 의미를 `//` 주석으로 명시한다(`PENDING,   // 결제 대기`). 값 의미는 식별자만으로 안 드러나므로.
- `BaseEntity` 공통 칼럼(id·created_at·updated_at)·자명한 칼럼은 생략. 이는 CLAUDE.md "주석 최소"의 **엔티티/enum 한정 예외**(칼럼·상태 의미는 운영·리뷰에 유용).

## 연관 관계

- `@ManyToOne(fetch = LAZY) @JoinColumn(name = "category_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))` — JPA 연관 매핑은 유지하되 **DB FK 제약은 걸지 않는다**. `@ForeignKey(NO_CONSTRAINT)` 로 (혹시 모를 Hibernate 스키마 생성에서도) FK 가 생기지 않도록 명시하고, Flyway DDL 에도 제약을 선언하지 않는다 — FK 칼럼 + 보조 인덱스만. 참조 무결성은 서비스 가드(존재 검증) + 정합 검증 테스트가 책임. 근거: 고트래픽 쓰기 경로의 부모 행 잠금 제거 · DDL/운영 유연성 · MSA 분리 대비 — [docs/design/erd.md](../../docs/design/erd.md) · [ADR-0003](../../docs/adr/0003-no-db-fk-constraints.md).
- 재고처럼 상위 엔티티와 독립적으로 차감·교체되는 행(HotDealStock·ProductStock)은 연관 매핑 없이 FK 값 칼럼(Long) + 전용 repository 조회로 둘 수 있다 — 단순 구현 선택이며 성능 결정이 아니다(차감은 조건부 UPDATE라 연관 매핑 유무와 무관하므로 `@OneToOne` 단건 조회 회피 같은 건 근거로 들지 않는다). 객체 탐색이 실제로 필요하면(예: `Order`→`Product`) 평범하게 `@ManyToOne`으로 매핑한다.
- `@OneToMany(mappedBy = "...", cascade = ..., orphanRemoval = ...)` + `@Builder.Default ... = new ArrayList<>()`.
- enum 필드: `@Enumerated(STRING) @Column(length = 20)`.

## 정적 팩토리 메서드

- `create(XxxRequest request, 연관엔티티 ...)` — Request 를 그대로 받고, 외부 연관만 별도 파라미터.
- 내부에서 builder 조립 + 도메인 검증 + 자식 생성까지 캡슐화:
  ```java
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
  ```

## 도메인 메서드 / 검증 캡슐화

- 비즈니스 로직·상태 변경·검증은 엔티티 도메인 메서드에 둔다 (`updateInfo`, `replaceOptions`, `moveParent`, `getOptionOrThrow` 등).
- 검증 실패는 `DomainException` + 도메인 `ExceptionCode` enum (static import):
  ```java
  import static ...product.exception.ProductExceptionCode.DUPLICATE_OPTION_NAME;
  if (names.size() != Set.copyOf(names).size()) throw new DomainException(DUPLICATE_OPTION_NAME);
  ```
- 파생 계산도 도메인 메서드로 노출(`getAncestorIds`, `getCategoryAncestorIds`) — 외부가 연관 엔티티에 직접 접근하지 않도록 대리. 연관의 id·값도 위임 메서드로 노출해(`getProductId()` → `product.getId()`) 호출부의 2-hop 체이닝을 막는다 — 상세 [service.md](service.md).

## 논리 삭제

- 공통 `BaseEntity` 에는 논리삭제 필드가 **없다**. 필요한 도메인만 개별로 `isDeleted` Boolean + `markDeleted()` 추가 (현재 `Coupon` 만 사용).
- 대부분 도메인은 상태 enum(`ProductStatus.STOP_SALE` 등)으로 노출/판매 제어.
