#!/usr/bin/env bash
# 토스 승인 API 가 코드별로 어떤 HTTP 상태를 주는지 샌드박스에 직접 물어 기록한다.
# 결제 결과 분류 RFC 5절("HTTP 상태 코드로는 가를 수 없다")의 근거를 원본으로 남기는 것이 목적이다.
#
#   TOSS_SECRET_KEY=test_sk_... bash docs/rfc/evidence/capture-toss-errors.sh
#
# 키는 반드시 **테스트** 시크릿 키여야 한다. 라이브 키를 쓰면 TossPayments-Test-Code 헤더가 무시된다.
# 결과는 이 폴더의 toss-error-responses.log 에 덮어쓴다.
set -uo pipefail
cd "$(dirname "$0")"

die() { printf '실행 불가: %s\n' "$1" >&2; exit 3; }
[ -n "${TOSS_SECRET_KEY:-}" ] || die "TOSS_SECRET_KEY 가 없다. 테스트 시크릿 키를 넣고 다시 실행한다"
case "$TOSS_SECRET_KEY" in test_sk_*) ;; *) die "테스트 키(test_sk_)가 아니다. 라이브 키로는 에러를 강제할 수 없다" ;; esac
command -v curl >/dev/null 2>&1 || die "curl 을 찾을 수 없다"

BASE_URL="https://api.tosspayments.com"
OUT="toss-error-responses.log"
AUTH=$(printf '%s:' "$TOSS_SECRET_KEY" | base64)

# 5절 표의 여섯. 400 자리에 거절·상태 불명·이미 성공한 결제가 함께 있다는 것이 이 표의 요점이다.
# 마지막 하나는 거절 목록에 뒤늦게 더한 코드라 상태값을 문서가 아니라 응답으로 확정한다.
CODES=(
  FDS_ERROR
  PROVIDER_ERROR
  ALREADY_PROCESSED_PAYMENT
  FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING
  FAILED_INTERNAL_SYSTEM_PROCESSING
  UNKNOWN_PAYMENT_ERROR
  UNAPPROVED_ORDER_ID
)

: > "$OUT"
{
  echo "# 토스 승인 API 강제 에러 응답 원본"
  echo "# 요청: POST $BASE_URL/v1/payments/confirm + TossPayments-Test-Code 헤더"
  echo "# 시크릿 키는 기록하지 않는다."
  echo
} >> "$OUT"

for CODE in "${CODES[@]}"; do
  echo "[$CODE] 요청"
  BODY=$(curl -sS -o - -w '\n__HTTP_STATUS__%{http_code}' \
    -X POST "$BASE_URL/v1/payments/confirm" \
    -H "Authorization: Basic $AUTH" \
    -H "TossPayments-Test-Code: $CODE" \
    -H "Content-Type: application/json" \
    -d '{"paymentKey":"evidence-capture","orderId":"evidence-capture","amount":19800}' 2>&1)

  STATUS=${BODY##*__HTTP_STATUS__}
  BODY=${BODY%$'\n'__HTTP_STATUS__*}
  {
    echo "## $CODE"
    echo "HTTP $STATUS"
    echo "$BODY"
    echo
  } >> "$OUT"
  echo "  -> HTTP $STATUS"
done

echo
echo "기록: $(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
echo "결제 결과 분류 RFC 5절 표와 대조할 것. 어긋나면 표를 응답에 맞춘다."
