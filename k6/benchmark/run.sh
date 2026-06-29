#!/usr/bin/env bash
# 동시성 벤치마크 자동 실행 — 일회용 컨테이너 기동 -> 전략 3개 측정 -> 완전 삭제.
# 사용: bash k6/benchmark/run.sh                  (처리량: 동시 1000·재고 2000, 거의 전원 차감)
#       STOCK=10 bash k6/benchmark/run.sh         (정확성: 재고<인원 품절 경합 — 초과 판매 0 검증)
#       ACCOUNTS=500 STOCK=10 bash k6/benchmark/run.sh
set -euo pipefail
cd "$(dirname "$0")"

ACCOUNTS="${ACCOUNTS:-1000}"
STOCK="${STOCK:-2000}"
BASE_URL="http://localhost:18080"
K6_SCRIPT="../order-flash-sale.js"
RESET_SQL="UPDATE hot_deal_stock SET remaining_quantity=$STOCK, version=0 WHERE hot_deal_id=1; UPDATE product_stock SET reserved_quantity=$STOCK WHERE product_id=1; DELETE FROM orders;"

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
  if [ "$SEEDED" = "0" ]; then mysql_exec < seed.sql; SEEDED=1; echo "  시드 완료"; fi
  mysql_exec -e "$RESET_SQL"
  redis_exec SET hotdeal:stock:1 "$STOCK" >/dev/null

  echo "  [$STRATEGY] k6 부하 (동시 $ACCOUNTS · 재고 $STOCK)"
  OUT="/tmp/k6-bench-$STRATEGY.log"
  k6 run -e ACCOUNTS="$ACCOUNTS" -e PRODUCT_ID=1 -e BASE_URL="$BASE_URL" "$K6_SCRIPT" > "$OUT" 2>&1 || true

  SUCCESS=$(grep 'order_success' "$OUT" | sed -E 's/.*: +([0-9]+).*/\1/' | head -1 || echo "?")
  P95=$(grep 'order_duration' "$OUT" | grep -oE 'p\(95\)=[0-9.]+(ms|s)' | head -1 | cut -d= -f2 || echo "?")
  AVG=$(grep 'order_duration' "$OUT" | grep -oE 'avg=[0-9.]+(ms|s)' | head -1 | cut -d= -f2 || echo "?")
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
