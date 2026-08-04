# hotdeal-commerce

Spring Boot 3.5.13 + Java 21 모놀리식 커머스(핫딜). 고트래픽 동시성 처리와 결제 후속의 부분 MSA 전환이 목표 — [build.gradle](build.gradle) · [프로젝트 개요](README.md)

전역 작업 규칙은 `~/.claude/CLAUDE.md`, Java/Spring 일반 패턴은 `spring-conventions` 스킬에 있다. **이 문서에는 이 저장소가 정한 것만 둔다.** 충돌하면 이 문서가 이긴다.

> **`src/main/java` 또는 `src/test/java` 를 이번 세션에서 처음 고치기 전에 `spring-conventions` 스킬을 호출한다.** 세션당 한 번.

## 스택

MySQL 8.4 + Flyway, Redis(Session/Cache), JPA + QueryDSL, MapStruct, Undertow, SpringDoc, JaCoCo. 인증은 무상태 JWT(RTR) — [build.gradle](build.gradle) · [application.yaml](src/main/resources/application.yaml)

PostgreSQL 이 아니다. 마이그레이션 SQL 은 MySQL 방언으로 쓴다.

## 패키지 구조

루트 `com.sparta.msa.commerce`. 최상위 축은 셋이다.

| 축 | 담는 것 |
|---|---|
| `domain/{도메인}` | 비즈니스 개념. 외부 연동의 **계약 인터페이스**도 그 능력이 필요한 도메인에 둔다 |
| `infrastructure/{역할}/{벤더}` | 벤더 구현·전송·요청 응답 타입·설정 — [infrastructure/paymentgateway/toss](src/main/java/com/sparta/msa/commerce/infrastructure/paymentgateway/toss) |
| `global` | 프레임워크 횡단 — config·security·exception·response·entity |

- 한 계층만 쓰는 것은 그 계층 아래, 둘 이상이 쓰면 도메인 루트. `dto` 는 5계층이 써서 루트에 둔다.
- **경계를 넘나드는 데이터 타입은 `dto/`** — web 경계는 `dto/request`·`dto/response`, 외부 시스템 경계는 `client/dto`. 엔티티는 경계를 넘는 데이터가 아니라 도메인 모델이라 `entity/`.
- **enum 은 쓰는 타입과 같은 자리에** 둔다. `constant/`·`enums/` 로 모으지 않는다 — `PaymentStatus` 는 `entity/`, `PgPaymentStatus` 는 `client/dto/`, `{Domain}ExceptionCode` 는 `exception/`.
- **요청 DTO 를 command 로 다시 매핑하지 않는다.** 인바운드 입구가 REST 하나라 복사본이 되고 Bean Validation 이 갈린다. 입구가 둘 이상 되면 그때.

## 계층

```
Controller → Facade → Service → Repository
```

- **Facade 4계층**: auth · hotdeal · order · payment (`domain/*/facade/` 실재)
- **지원 도메인은 Service 까지만**: product · stock · user — Controller 없이 타 도메인 Facade 가 진입점
- 신규 도메인은 4계층. 단순 CRUD 도 Facade 를 경유한다. 기존 3계층 도메인을 강제 전환하지는 않는다.
- **의존은 한 방향으로만 흐른다** — 구매 쪽(order·payment)이 카탈로그·재고(hotdeal·product·stock)를 참조하고 역방향은 없다. 타 도메인 Repository 를 직접 부르지 않는다. 근거는 [애플리케이션 구조 ADR](docs/adr/architecture.md)

**로직을 어디 둘지는 "다른 유즈케이스가 이걸 다르게 하면 버그인가?"로 판단한다.** 재사용 여부로 판단하면 공통 서비스가 잡동사니 통이 된다. 상세는 `spring-conventions` 의 service.md.

## 하드룰

1. **들여쓰기 2칸, 탭 금지** — [.editorconfig](.editorconfig)
2. **컨트롤러는 raw DTO 를 반환한다.** [ApiResponseAdvice](src/main/java/com/sparta/msa/commerce/global/response/advice/ApiResponseAdvice.java) 가 `{result, data, error}` 로 자동 래핑한다. 직접 감싸면 이중 래핑
3. **예외는 `throw new DomainException({Domain}ExceptionCode.X)`** — [global/exception](src/main/java/com/sparta/msa/commerce/global/exception). `IllegalStateException`/`IllegalArgumentException` 을 쓰지 않는다
4. **DB FK 제약을 걸지 않는다** — `@JoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))` + Flyway DDL 에도 미선언. 참조 무결성은 서비스 가드가 책임 — [참조 무결성 ADR](docs/adr/integrity.md)
5. **엔티티는 [BaseEntity](src/main/java/com/sparta/msa/commerce/global/entity/BaseEntity.java) 를 상속**하고 정적 팩토리 `create(Request, 연관)` 로만 만든다
6. **컬럼·enum 상수에 한국어 의미 주석**을 단다(`@Comment` 는 Flyway DDL 의 `COMMENT` 와 문구 일치). 이것만 "주석 최소" 원칙의 예외다

## 외부 연동 네이밍

계약 `{역할}Client` / 어댑터 `{벤더}{도메인}Client` / HTTP 전송 `{벤더}HttpClient` — `PaymentGatewayClient` · `TossPaymentClient` · `TossHttpClient`. 근거는 [결제 ADR 3절](docs/adr/payment.md)

- 계약은 `domain/{도메인}/client/`, 결과 타입은 `client/dto/`, 벤더 구현·전송·설정은 `infrastructure/{역할}/{벤더}/`
- **역할을 벤더보다 위에** 둔다. 같은 역할의 구현들이 모여야 교체 후보가 보인다
- **혼자 읽히는 이름은 풀네임**(`PaymentGatewayClient`·`PAYMENT_GATEWAY_ERROR`), **접두는 축약**(`PgConfirmResult`·`pgPaymentKey`). 혼자 선 `Pg` 는 PostgreSQL 로 읽힌다

## 재고 차감 — 전략 3종이 한 계약을 공유한다

`stock.deduct.strategy` 로 구현체가 갈린다 — [application.yaml:50](src/main/resources/application.yaml)

| 값 | 구현체 |
|---|---|
| `conditional` (기본) | [ConditionalHotDealStockService](src/main/java/com/sparta/msa/commerce/domain/stock/service/ConditionalHotDealStockService.java) — 조건부 UPDATE |
| `redis` | [RedisHotDealStockService](src/main/java/com/sparta/msa/commerce/domain/stock/service/RedisHotDealStockService.java) |

`HotDealStockService`(인터페이스) + `AbstractHotDealStockService`(공통) + 구현 2개 구조다. **한 전략만 고치면 나머지가 계약을 깬다.** 벤치마크 결과와 전환 선행 조건은 [동시성 ADR](docs/adr/concurrency.md) · [벤치마크 RFC](docs/rfc/concurrency-benchmark.md)

인터페이스·추상 클래스·Redis 구현체에는 `@Transactional` 을 붙이지 않는다. DB 트랜잭션이 없거나 의미가 없다.

## 마이그레이션

`src/main/resources/db/migration/V{n}__*.sql`, MySQL 방언. 현재 V1~V6.

**이미 적용된 파일을 고치지 않는다.** 체크섬이 어긋나 통합 테스트가 전부 죽고 원인이 로그에 드러나지 않는다. 스키마 변경은 항상 새 버전 파일로.

엔티티 매핑을 바꾸면 마이그레이션을 함께 낸다.

## 통합 테스트

**공통 베이스 클래스가 없다.** 의도된 것이니 새로 만들지 않는다. 각 테스트가 직접 단다.

```java
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @WithMockUser(roles = "ADMIN")
```

- testcontainers MySQL 8.4 + Redis. Flyway 가 운영과 동일하게 실행된다(`ddl-auto: none`)
- 격리는 `@BeforeEach` 의 `repository.deleteAll()`. **클래스 레벨 `@Transactional` 을 붙이지 않는다** — mockMvc 이후 repository 검증이 깨진다
- 단언은 래핑된 구조를 탄다: `$.result` / **`$.data.{필드}`** / `$.error.code == "{ExceptionCode 이름}"`
- `@Nested`+`@DisplayName`(한국어 시나리오), 메서드명은 영어 camelCase

## 빌드 · 테스트

```
./gradlew compileJava                          컴파일
./gradlew test                                 전체 테스트 (Docker 필요)
./gradlew test --tests '*XxxIntegrationTest'   단일
./gradlew test jacocoTestReport                커버리지
```

**`./gradlew test` 는 Docker 가 떠 있어야 한다.** testcontainers 가 MySQL·Redis 를 띄운다. 컨테이너가 못 뜨면 애플리케이션 로딩부터 실패하는데 스택 트레이스만 보면 코드 버그처럼 보인다. 테스트가 무더기로 깨지면 Docker 부터 확인한다.

## 코드 리뷰

`code-reviewer` 서브에이전트는 **사용자가 리뷰를 요청했을 때만** 부른다. 자발적으로 부르지 않는다. 존재 이유는 컨텍스트 절약이 아니라 **구현 과정을 보지 않은 독립된 눈**이다.

기계적 위반(계층·래핑·탭·마이그레이션 수정)은 `.claude/scripts/check.sh` 가 검출한다.

## 도구

Java 소스의 심볼 단위 분석·편집은 Serena MCP 우선 — `find_symbol`, `find_referencing_symbols`, `replace_symbol_body`, `get_symbols_overview`, `get_diagnostics_for_file`. 비-Java(YAML·MD·SQL)와 빌드·git 은 기본 도구.

## 문서

결정은 `docs/adr/`(주제별 ADR — 색인은 [adr/README.md](docs/adr/README.md)), 그 근거와 실측은 `docs/rfc/`, 설계 문서는 `docs/design/`. 새 도메인·API 작업은 설계 문서를 먼저 쓴다(`api-design` 스킬).

**작성 규약** — 이 절이 정본이다. 다른 곳에 사본을 두지 않는다.

- 타 문서의 특정 절을 가리킬 때는 **절 번호를 텍스트에 적고 링크는 파일 레벨**로 건다 — `[핫딜 ADR 4절](docs/adr/hotdeal.md)`. `#앵커` 를 쓰지 않는다. IntelliJ 가 자동 슬러그 앵커를 지원하지 않아 파일 레벨만 IntelliJ·GitHub 양쪽에서 작동한다
- **영어 파일명을 본문에 노출하지 않는다.** 링크 텍스트는 한국어 문서 이름으로
- **다른 문서의 수치를 복제하지 않는다.** 모으고 싶으면 링크만 건다 — 원본이 바뀌면 사본은 아무도 안 고친다

## 커밋 체크포인트

절차는 전역 `~/.claude/CLAUDE.md`. 이 저장소의 범위:

- **적용**: 도메인 개발·수정, 설계 문서, 마이그레이션 SQL
- **메타 설정**(`.claude/**`, `CLAUDE.md`): 작은 변경은 끝낸 뒤 1회 커밋. **여러 파일을 지우거나 구조를 바꾸는 작업이면 되돌릴 수 있도록 단계별로 쪼갠다**
- `.claude/` 도 git 추적 대상이라 커밋한다
