# 결제 결과 분류 — 토스 응답을 네 갈래로 접는 근거

토스 승인 요청이 끝났을 때 그것을 성공·실패·모름 중 무엇으로 볼지 정한 과정이다.
결정 자체는 [결제 ADR 5절](../adr/payment.md)에 있고, 이 문서는 경계를 어디에 왜 그었는지를 보존한다.

## 1. 왜 어려운가

승인 요청 하나가 끝나는 방식은 셋인데, 우리가 알고 싶은 것은 하나다.

- 2xx 응답을 받았다 — 돈이 나갔다
- 에러 응답을 받았다 — 돈이 나갔는지 **응답만으로는 모른다**
- 응답을 못 받았다 — 요청이 닿았는지조차 모른다

우리가 알아야 하는 것은 오직 **돈이 나갔나**이다. 그런데 그것을 직접 알려주는 필드가 없다.

이 판단이 틀렸을 때의 손해가 양쪽으로 다르다.

| 잘못 판단 | 결과 | 크기 |
|---|---|---|
| 돈이 나갔는데 실패로 보고 되돌림 | 주문 취소 + 재고 방출인데 결제는 살아 있음 | 사용자가 돈을 잃는다 |
| 돈이 안 나갔는데 모름으로 남김 | 결제가 미확정으로 남았다가 해소가 정리 | 정리가 몇 분 늦는다 |

**한쪽은 사고, 다른 쪽은 낭비다.** 이 비대칭이 아래 모든 선택을 결정한다.

## 2. 기준은 하나 — 돈이 확실히 안 빠졌나

응답을 받았는지로 먼저 갈리고, 그다음에 각각 다른 질문을 한다.

```
응답을 받았나?
├─ 받았다 → 에러 코드가 "돈 안 빠진 거절" 목록에 있나?
│           ├─ 있다  → 거절
│           └─ 없다  → 모름
└─ 못 받았다 → 요청이 닿기는 했나?
              ├─ 안 닿았다(연결 실패·주소 못 찾음) → 통신오류
              └─ 닿았을 수 있다(응답 대기 중 끊김)  → 모름
```

- 어느 갈래에서도 **애매하면 "모름"으로 떨어진다.** 위 비대칭 때문이다
- HTTP 상태 코드는 보지 않는다(5절)
- 이 분류는 전부 어댑터 안에 있다. 위 계층은 네 갈래만 받는다

## 3. 응답을 받은 경우 — 거절 쪽을 열거한다

에러 코드를 다룰 때 목록을 어느 쪽에 만들지가 갈림길이다.

- **"모름"을 열거**하면, 목록에 없는 새 코드가 자동으로 거절이 된다 → 모르는 코드에 주문을 되돌린다 → 사고 쪽으로 샌다
- **"거절"을 열거**하면, 목록에 없는 새 코드가 자동으로 모름이 된다 → 해소가 정리한다 → 낭비 쪽으로 샌다

그래서 열거하는 쪽은 **거절**이다. 토스가 나중에 코드를 추가해도 자동으로 안전한 쪽에 떨어진다.

### 목록에 넣으면 안 되는 것 — 함정 세 부류

| 부류 | 예 | 왜 넣으면 안 되나 |
|---|---|---|
| 돈이 이미 빠진 것 | `ALREADY_PROCESSED_PAYMENT` | "이미 처리된 결제"는 성공한 결제다. 이것을 거절로 넣으면 **성공한 결제를 되돌리는** 바로 그 사고가 난다 |
| 처리가 어디까지 갔는지 모르는 것 | `PROVIDER_ERROR` · `CARD_PROCESSING_ERROR` · `FAILED_*` | 카드사·PG 내부에서 난 오류라 승인이 섰는지 안 섰는지 알 수 없다 |
| 우리 설정이 틀린 것 | `INVALID_API_KEY` · `UNAUTHORIZED_KEY` · `INVALID_REQUEST` | 사용자의 결제가 거절된 게 아니라 우리 버그다. 결제 거절로 위장하면 원인이 사라진다 |

- 세 부류 모두 모름으로 떨어뜨린다. 첫째는 사고를 막으려고, 둘째는 사실이라서, 셋째는 조사할 수 있게 남기려고다

### 목록에 넣은 예외 — 존재하지 않는 결제

`NOT_FOUND_PAYMENT` 와 `NOT_FOUND_PAYMENT_SESSION` 은 거절에 넣었다.

- 결제 객체 자체가 없으니 돈이 빠질 수가 없다. 기준("돈이 확실히 안 빠졌나")에 정확히 맞는다
- 넣지 않으면 위조하거나 오타 난 키가 미확정으로 남고, 해소가 다시 물어봐도 계속 없다고 답한다
  - 영원히 확정되지 않는 행이 쌓이고, 그 주문이 잡은 **핫딜 재고가 영구히 묶인다**
  - 아무 문자열이나 보내 재고를 잠글 수 있으므로 악용 경로가 된다
- 정상 사용자는 결제창이 발급한 유효한 키를 쓰므로 이 코드를 볼 일이 없다. 정상 결제를 잘못 취소할 위험이 없다
- 해소 쪽에도 같은 규칙을 둔다. 조회에서 결제를 못 찾으면 실패로 확정한다

## 4. 응답을 못 받은 경우 — 요청이 닿았나

응답이 없을 때는 코드가 없으니 **예외의 원인**으로 가른다.

| 원인 | 판단 | 근거 |
|---|---|---|
| 연결 실패 · 연결 타임아웃 · 주소를 못 찾음 | 통신오류 | 연결 자체가 안 섰으니 요청이 토스에 닿지 않았다. 돈이 안 나간 것이 확정 |
| 응답 대기 중 타임아웃 · 소켓 끊김 | 모름 | 연결은 섰다. 요청이 닿아 승인이 처리됐을 수 있다 |

- 이 구분이 있어야 통신오류를 "돈 안 나감 확정"으로 다루고 주문을 `PENDING` 으로 되돌릴 수 있다
- 그래서 연결 타임아웃을 2초로 짧게 잡았다. 길게 잡으면 "안 닿았다"가 늦게 확정된다
- 응답 타임아웃은 60초다. 토스가 결제 처리 API에 권장하는 값이고, 넘으면 모름이 된다

## 5. HTTP 상태 코드로는 가를 수 없다

처음 떠오르는 방법은 4xx면 거절, 5xx면 모름으로 두는 것이다. 성립하지 않는다.

토스는 요청 헤더로 특정 에러를 강제할 수 있어, 코드별로 어떤 상태값이 오는지 샌드박스에 직접 물어 확인했다.

| 코드 | 상태값 | 실제 의미 |
|---|:--:|---|
| `FDS_ERROR` | 403 | 돈이 안 빠진 확정 거절 |
| `PROVIDER_ERROR` | **400** | 카드사 내부 오류 — **상태를 모름** |
| `ALREADY_PROCESSED_PAYMENT` | 400 | 돈이 **빠진** 성공 결제 |
| `FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING` | 500 | 상태를 모름 |
| `FAILED_INTERNAL_SYSTEM_PROCESSING` | 500 | 상태를 모름 |
| `UNKNOWN_PAYMENT_ERROR` | 500 | 상태를 모름 |

- **400 자리에 세 판단이 다 있다.** 거절도, 상태 불명도, 이미 성공한 결제도 같은 400으로 온다
- `PROVIDER_ERROR` 가 400이면서 상태 불명인 것이 결정적이다. 4xx를 거절로 뭉뚱그리는 규칙은 여기서 바로 깨진다
- 5xx를 전부 모름으로 두는 것은 안전한 쪽이라 해가 없지만, 그 규칙만으로는 400 안의 셋을 못 가른다

그래서 상태 코드를 무시하고 **응답 본문의 코드만** 본다. 본문에서 코드를 못 꺼내면(형식이 다르거나 본문이 없으면) 모름으로 떨어진다.

## 6. 거절 목록 전문

`TossPaymentClient` 가 거절로 접는 코드 31개다. 전부 "사용자의 결제 수단이 막혀 돈이 안 나간" 경우다.

| 부류 | 코드 |
|---|---|
| 카드사·계좌가 거절 | `REJECT_CARD_COMPANY` · `REJECT_CARD_PAYMENT` · `REJECT_ACCOUNT_PAYMENT` · `REJECT_TOSSPAY_INVALID_ACCOUNT` · `INVALID_REJECT_CARD` |
| 카드를 쓸 수 없음 | `INVALID_CARD_NUMBER` · `INVALID_CARD_EXPIRATION` · `INVALID_STOPPED_CARD` · `INVALID_CARD_LOST_OR_STOLEN` · `INVALID_PASSWORD` · `INVALID_ACCOUNT_INFO_RE_REGISTER` |
| 한도·금액 초과 | `BELOW_MINIMUM_AMOUNT` · `EXCEED_MAX_AMOUNT` · `EXCEED_MAX_PAYMENT_AMOUNT` · `EXCEED_MAX_ONE_DAY_AMOUNT` · `EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT` · `EXCEED_MAX_DAILY_PAYMENT_COUNT` · `EXCEED_MAX_AUTH_COUNT` · `EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT` · `EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT` · `EXCEED_MAX_CARD_INSTALLMENT_PLAN` |
| 할부 조건 불가 | `NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT` · `INVALID_CARD_INSTALLMENT_PLAN` · `NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN` |
| 결제 수단 제약 | `NOT_ALLOWED_POINT_USE` · `RESTRICTED_TRANSFER_ACCOUNT` · `NOT_AVAILABLE_BANK` · `NOT_AVAILABLE_PAYMENT` |
| 위험 거래 차단 | `FDS_ERROR` |
| 결제가 존재하지 않음 | `NOT_FOUND_PAYMENT` · `NOT_FOUND_PAYMENT_SESSION` |

## 7. 아직 못 밝힌 것

- **5절 확인의 응답 원본을 보존하지 않았다.** 확인한 상태값은 어댑터 테스트에 그대로 고정돼 있지만 요청·응답 로그가 남아 있지 않다. 토스가 코드별 응답을 바꾸면 알아차릴 방법이 없고, 재확인하려면 강제 에러 헤더로 다시 요청해야 한다
- **목록의 완전성을 확인하지 못했다.** 토스 에러 코드 전수를 훑어 분류한 것이 아니라, 거절이 분명한 것부터 담았다. 빠진 거절 코드는 불필요한 미확정을 만들 뿐 사고로 이어지지 않는다는 것이 이 설계의 안전망이다
- **얼마나 자주 미확정이 나는지 모른다.** 테스트 모드라 실제 분포가 없고, 해소가 몇 건을 처리하게 될지 예측하지 않았다. 판정하려면 실거래 로그가 필요하다
- **분류가 PG 중립인지 확인하지 못했다.** "돈이 확실히 안 빠졌나"라는 기준은 PG와 무관하지만, 다른 PG가 이 판단에 필요한 정보를 응답에 주는지는 둘째 PG를 붙여야 알 수 있다

## 8. 어댑터 테스트 리스트

가짜 HTTP 서버에 응답을 심어 각 갈래를 명세한다. 통합 테스트는 이 어댑터를 대역으로 바꿔 끼우므로, 분류 자체를 검증하는 것은 여기뿐이다.

**승인 요청 분류 (1~11)**

| # | 테스트 | 응답 | 기대 |
|---|---|---|---|
| 1 | `mapsApprovedOnSuccess` | 2xx 승인 | 승인 |
| 2 | `mapsRejectedOn4xx` | 거절 목록에 있는 코드 | 거절 |
| 3 | `mapsRejectedOnFdsError` | `FDS_ERROR`(403, 위험 거래 차단) | 거절 |
| 4 | `mapsRejectedOnNotFoundPayment` | `NOT_FOUND_PAYMENT`(404, 위조 키) | 거절 — 영구 미확정 차단 |
| 5 | `mapsInDoubtOnStateUnknownCodes` | `PROVIDER_ERROR`(400) · `FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING`(500) · `FAILED_INTERNAL_SYSTEM_PROCESSING`(500) · `UNKNOWN_PAYMENT_ERROR`(500) | 모름 — 상태 코드로 못 가름 |
| 6 | `mapsInDoubtOnAlreadyProcessedPayment` | `ALREADY_PROCESSED_PAYMENT`(400, 돈 빠짐) | 모름 — 거절로 넣으면 사고 |
| 7 | `mapsInDoubtOnUnknownCode` | 목록에 없는 처음 보는 코드 | 모름 — 안전 기본값 |
| 8 | `mapsInDoubtOnUnrecognized5xx` | 본문이 JSON이 아니라 코드를 못 꺼냄(502) | 모름 |
| 9 | `mapsInDoubtOnReadTimeout` | 응답 대기 중 끊김 | 모름 |
| 10 | `mapsGatewayErrorOnConnectFailure` | 서버가 없어 연결 실패 | 통신오류 |
| 11 | `sendsIdempotencyKeyHeader` | 승인 요청 헤더 | `Idempotency-Key` 가 결제 키와 같다 |

**결제 조회 매핑 (12~16)** — 해소가 쓰는 경로다. 분류가 아니라 상태 이름 대응이다.

| # | 테스트 | 응답 | 기대 |
|---|---|---|---|
| 12 | `mapsGetPaymentStatusDone` | 키로 조회, 승인 완료 | 승인 완료로 매핑 |
| 13 | `mapsGetPaymentStatus` | 키로 조회, 상태 여러 개 | 같은 이름으로 매핑 |
| 14 | `mapsGetPaymentUnknownStatus` | 키로 조회, 모르는 상태 | `UNKNOWN` — 안전 기본값 |
| 15 | `mapsFindPaymentByOrderIdDone` | 주문번호로 조회, 승인 완료 | 결제 키까지 실어 반환 |
| 16 | `mapsFindPaymentByOrderIdNotFound` | 주문번호로 조회, 404 | 빈 결과 — 매입 미발생 확정 |
