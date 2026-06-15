---
globs: **/facade/*.java, **/service/*.java, **/consumer/**/*.java
---

# 파사드 / 서비스 규칙

commerce 는 **신규 도메인부터 4계층**(Controller → Facade → Service → Repository)을 적용한다. 기존 도메인(3계층, `auth` 만 Facade)은 그대로 두고, 신규/대규모 개편 도메인에 Facade 를 **전면 도입**한다.

```
Controller → Facade → Service → Repository
                      entity | dto | mapper | exception
```

> **apartmant식 전면 적용**: cross-domain 조정이 없는 단일 도메인 단순 CRUD 도 `Controller → Facade → Service` 흐름을 따른다(일관성). Facade 가 단순 위임만 하더라도 둔다.

## 계층 의존

- **Controller**: Facade 만 호출 (Service/Repository 직접 호출 금지).
- **Facade**: 타 도메인 **Service 호출 가능** (Repository 직접 호출 금지). cross-domain 조정 + Response(DTO) 조립(MapStruct) 담당.
- **Service**: 자기 도메인 Repository + 같은 도메인 공통 Service 만 의존. **타 도메인 Service 직접 호출 금지**(Facade 경유). 엔티티/도메인 로직 담당, 보통 엔티티를 반환.

## 서비스 분류 — 리소스 그룹 (유즈케이스 = 메서드)

서비스는 **리소스(애그리거트) 단위로 묶고 유즈케이스는 메서드로** 둔다. 유즈케이스마다 클래스를 쪼개지 않는다(`CreateOrderService` 식 동사-클래스 분할 ❌ — 정말 무거운 단일 흐름만 예외). Controller/Facade 도 같은 리소스 단위, Consumer 만 이벤트 단위.

- 명령 `{Domain}Service`: 그 도메인의 쓰기/도메인 로직(여러 유즈케이스 = 여러 메서드), 보통 엔티티 반환.
- 조회 `{Domain}QueryService`: 읽기 전용 분리(command/query).
- 공통 `Common{Domain}Service`: **여러 Facade/Service 가 똑같이 써야 하는 정규 도메인 연산**(예: `getOrderOrThrow`) + 타 도메인이 이 도메인 데이터를 얻는 진입점.
- 역할별 `{Domain}{Role}Service`: 특정 역할 전용(기존 컨벤션, 혼용 가능).

> 기존 모놀리식의 `{Domain}Service`(파사드가 재사용하던 공유 서비스)는 **그대로 유지** — 강제 리네임 없음. 신규 도메인/week-12 부터 위 분류 적용.

## 로직 배치 기준 (무엇을 `Common{Domain}Service` 로?)

"재사용되나?"가 아니라 **"다른 유즈케이스가 이걸 다르게 하면 버그인가?"** 로 판단한다:

1. 단일 애그리거트 불변식·상태전이 → **Entity**(rich) (`order.markPaid()`).
2. 모든 유즈케이스가 **동일해야** 하는 정규 연산(정규 조회·공유 검증·다중 애그리거트 규칙 — 다르면 버그) → **`Common{Domain}Service`**.
3. 이 유즈케이스 특유의 순서·정책·분기(달라도 정당) → 해당 **`{Domain}Service` 메서드**.
4. 애매하면 → 해당 Service 메서드에 두고, 둘째 유즈케이스가 *동일* 연산을 요구할 때 `Common{Domain}Service` 로 승격(선반영 금지).

> 이름은 `Common`(공유)이지만 **넣는 기준은 "불변/정규(다르면 버그)"** — "공통인가?"라는 재사용 질문으로 판단하면 잡동사니 통이 된다.
> 의존 경계(Service 의 타 도메인 직접 의존 금지)는 **코드 리뷰**가 안전장치 — 자동 강제(ArchUnit) 미도입.

## Kafka 컨슈머 (인바운드 어댑터 = 컨트롤러 동급)

`@KafkaListener` 컨슈머는 **컨트롤러와 같은 인바운드 어댑터**다. REST 컨트롤러처럼 **Facade 를 거친다**(Service 직접 호출 금지).

- 흐름: `{Event}Consumer → {Domain}Facade → {Domain}Service`.
- 네이밍: **소비 이벤트 기준** `{Event}Consumer`(`OrderCreatedConsumer`) — 컨트롤러는 리소스, 컨슈머는 이벤트가 자연 단위.
- 페이로드는 **타입 파라미터로 바로 받는다**(`consume(OrderCreatedEvent event, Acknowledgment ack)`). JSON→타입 변환은 **`StringJsonMessageConverter` 빈 하나**가 전 컨슈머 공통 처리 — 컨슈머마다 `ObjectMapper`/try-catch 금지. 역직렬화 실패는 에러 핸들러→DLT 담당.
- 책임: **Facade 위임 + ack 만**(역직렬화·비즈니스 로직 0). `acknowledgment.acknowledge()` 는 **Facade 트랜잭션 커밋 뒤**(컨슈머에서, 트랜잭션 밖) 호출 — 먼저 ack 후 DB 롤백되면 유실.

## 트랜잭션

- **Service**: 클래스 레벨 `@Transactional(readOnly = true)` + 쓰기 메서드 `@Transactional` 오버라이드.
- **Facade**: 클래스 레벨 `@Transactional` **없음** + 메서드별 개별 지정(쓰기 `@Transactional`, 조회 `@Transactional(readOnly = true)`).

## 네이밍

- Facade: `{Domain}{Role}Facade` / `{Domain}Facade`. Controller 는 Facade 를 주입.

## 예시

```java
// Controller — Facade 만 호출, raw DTO 반환 (ApiResponseAdvice 자동 래핑)
@PostMapping
public CreateProductResponse createProduct(@RequestBody @Valid CreateProductRequest request) {
    return productAdminFacade.createProduct(request);
}

// Facade — cross-domain Service 조합 + Response 조립 (클래스 레벨 @Transactional 없음)
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
        Product product = Product.create(request, category);   // 검증은 엔티티 도메인 메서드
        return productRepository.save(product);
    }
}
```

## 메서드 구조

- Service: 검증 → 엔티티 생성/변경 → 저장. save/saveAll 은 메서드 하단.
- 예외는 `throw new DomainException({Domain}ExceptionCode.CODE)`.

## 어노테이션 순서

- Service: `@Service` → `@RequiredArgsConstructor` → `@Transactional(readOnly = true)`
- Facade: `@Service` → `@RequiredArgsConstructor` (클래스 레벨 `@Transactional` 없음 — 메서드별 지정)

## 검증 메서드 self-guarding

검증 메서드는 입력 null/empty 분기를 **자기 안에서** 처리한다. 호출부에 `if (list != null && !list.isEmpty())` 분기나 `hasXxx` 변수를 두지 않는다 (호출부가 if 분기 없이 직선으로 흐르도록).

```java
// ❌ 호출부 분기
if (options != null && !options.isEmpty()) validateDuplicateNames(options);

// ✅ 검증 메서드가 자체 처리 (null/empty 면 안에서 return)
validateDuplicateNames(options);

private void validateDuplicateNames(List<String> names) {
    if (names == null || names.isEmpty()) return;
    // ... 검증 ...
}
```

## saveAll 빈 리스트 안전 + 호출 메서드 빈 입력 처리

Spring Data JPA `saveAll(빈 리스트)` 는 에러 없이 통과한다(null 만 거부). 따라서 호출부에 `if (list.isEmpty()) skip` 분기가 불필요하도록, 호출되는 메서드들도 빈 입력을 안전 처리한다(빈 입력 시 빈 리스트 반환, 또는 검증/외부 호출 skip).

## 기존 코드(3계층) 처리

- 기존 도메인은 Service 가 타 도메인 Service 를 직접 호출하는 3계층 — **유지**(전환 강제하지 않음).
- 신규 도메인 또는 대규모 개편 시에만 4계층 Facade 도입.
