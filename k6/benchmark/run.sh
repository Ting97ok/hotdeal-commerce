#!/usr/bin/env bash
# 동시성 벤치마크 자동 실행 — 일회용 컨테이너 기동 -> 전략(conditional·redis) 측정 -> 완전 삭제.
# 사용: bash k6/benchmark/run.sh                  (처리량: 동시 1000·재고 2000, 거의 전원 차감)
#       STOCK=10 bash k6/benchmark/run.sh         (정확성: 재고<인원 품절 경합 — 초과 판매 0 검증)
#       ACCOUNTS=500 STOCK=10 bash k6/benchmark/run.sh
set -euo pipefail
cd "$(dirname "$0")"

ACCOUNTS="${ACCOUNTS:-1000}"
STOCK="${STOCK:-2000}"
BASE_URL="http://localhost:18080"
K6_SCRIPT="../order-flash-sale.js"
# 문서가 인용하는 수치의 원본이라 저장소에 남긴다. 파일명에 인원·재고를 넣어야
# 매트릭스 세 구간(저경합·고경합·품절)이 서로 덮지 않는다.
RESULTS="results/single"
TOKENS="$PWD/results/tokens.json"
RESET_SQL="UPDATE hot_deal_stock SET remaining_quantity=$STOCK WHERE hot_deal_id=1; UPDATE product_stock SET reserved_quantity=$STOCK WHERE product_id=1; DELETE FROM orders;"

mysql_exec() { docker compose exec -T mysql mysql -uroot -proot commerce "$@"; }
redis_exec() { docker compose exec -T redis redis-cli "$@"; }

cleanup() { echo "[정리] 컨테이너·볼륨 삭제"; docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

wait_healthy() {
  local cid
  for _ in $(seq 1 60); do
    cid=$(docker compose ps -q "$1" 2>/dev/null || true)
    [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || true)" = "healthy" ] && return 0
    sleep 1
  done
  echo "[$1] healthy 대기 실패"; docker compose logs "$1" 2>&1 | tail -20; return 1
}
wait_app() {
  for _ in $(seq 1 90); do
    curl -sf "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && return 0
    sleep 2
  done
  echo "[app] UP 대기 실패"; docker compose logs app 2>&1 | tail -40; return 1
}

mkdir -p "$RESULTS"

echo "[1/4] 일회용 인프라 기동 (mysql:13306, redis:16379, tmpfs)"
docker compose up -d mysql redis
wait_healthy mysql
wait_healthy redis
echo "  인프라 healthy"

echo "[2/4] 앱 이미지 빌드 (캐시)"
docker compose build app >/dev/null

declare -a ROWS
SEEDED=0
for STRATEGY in conditional redis; do
  echo "=== [$STRATEGY] 앱 기동 ==="
  STRATEGY=$STRATEGY docker compose up -d --force-recreate app >/dev/null
  wait_app
  echo "  UP"
  if [ "$SEEDED" = "0" ]; then
    mysql_exec < seed.sql; SEEDED=1; echo "  시드 완료"
    # 계정 발급을 k6 밖에서 먼저 끝낸다. setup() 안에 두면 계정 생성 시간이 k6 총
    # 실행 시간에 들어가 iterations/s 가 주문 처리율이 아니게 된다.
    BASE_URL="$BASE_URL" bash provision-accounts.sh "$ACCOUNTS" "$TOKENS"
  fi
  mysql_exec -e "$RESET_SQL"
  redis_exec SET hotdeal:stock:1 "$STOCK" >/dev/null

  echo "  [$STRATEGY] k6 부하 (동시 $ACCOUNTS · 재고 $STOCK)"
  OUT="$RESULTS/single-$STRATEGY-a${ACCOUNTS}-s${STOCK}.log"
  JSON="$RESULTS/single-$STRATEGY-a${ACCOUNTS}-s${STOCK}.json"
  k6 run --summary-export="$JSON" -e ACCOUNTS="$ACCOUNTS" -e PRODUCT_ID=1 \
    -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS" "$K6_SCRIPT" > "$OUT" 2>&1 || true

  jval() { jq -r "$1 // empty" "$JSON" 2>/dev/null | head -1; }
  fmt() { awk -v v="${1:-}" 'BEGIN{ if (v=="") print "?"; else if (v+0>=1000) printf "%.2f초", v/1000; else printf "%.0fms", v }'; }
  SUCCESS=$(jval '.metrics.order_success.count // .metrics.order_success.values.count'); SUCCESS=${SUCCESS:-0}
  P95=$(fmt "$(jval '.metrics.order_duration["p(95)"] // .metrics.order_duration.values["p(95)"]')")
  AVG=$(fmt "$(jval '.metrics.order_duration.avg // .metrics.order_duration.values.avg')")
  SOLD=$(mysql_exec -N -e "SELECT COALESCE(SUM(quantity),0) FROM orders;" 2>/dev/null | tr -d '[:space:]' || echo 0)
  if [ "$STRATEGY" = "redis" ]; then
    LEFT=$(redis_exec GET hotdeal:stock:1 2>/dev/null | tr -d '[:space:]' || echo 0)
  else
    LEFT=$(mysql_exec -N -e "SELECT remaining_quantity FROM hot_deal_stock WHERE hot_deal_id=1;" 2>/dev/null | tr -d '[:space:]' || echo 0)
  fi
  SUM=$(( ${LEFT:-0} + ${SOLD:-0} ))
  OVERSELL=$([ "$SUM" = "$STOCK" ] && echo "0" || echo "위반($SUM)")
  ROWS+=("$STRATEGY|$SUCCESS|$OVERSELL|$P95|$AVG")
  docker compose stop app >/dev/null 2>&1
  echo "  [$STRATEGY] 성공=$SUCCESS 오버셀=$OVERSELL p95=$P95 avg=$AVG"
done

echo ""
echo "================ 결과 (동시 $ACCOUNTS · 재고 $STOCK) ================"
printf "%-12s %-13s %-10s %-9s %-9s\n" "전략" "성공/$ACCOUNTS" "오버셀" "p95" "avg"
for row in "${ROWS[@]}"; do
  IFS='|' read -r s su ov p a <<< "$row"
  printf "%-12s %-13s %-10s %-9s %-9s\n" "$s" "$su" "$ov" "$p" "$a"
done
