#!/usr/bin/env bash
# 다중 인스턴스 동시성 벤치마크 — nginx LB 뒤 app 1 vs 3, conditional·redis 전략.
# 단일 스택(run.sh)과 격리. 측정 3종: 처리량/역전(VU 스윕) · 오버셀0 · 커넥션 총량.
# raw 로그는 results/ 에 조합별 전량 보존.
#
# 사용:
#   bash k6/benchmark/run-multi.sh                          (전체)
#   VUS="1000" bash k6/benchmark/run-multi.sh               (VU 한 점만)
#   STRATEGIES="conditional" INSTANCES="3" bash ...run-multi.sh
set -euo pipefail
cd "$(dirname "$0")"

STRATEGIES="${STRATEGIES:-conditional redis}"
INSTANCES="${INSTANCES:-1 3}"
VUS="${VUS:-1000 2000 3000}"
OVERSELL_STOCK="${OVERSELL_STOCK:-10}"
OVERSELL_VU="${OVERSELL_VU:-1000}"
CONN_VU="${CONN_VU:-2000}"                    # 커넥션 폴링을 붙일 대표 VU (인스턴스 3 에서만)
BASE_URL="http://localhost:18080"             # nginx LB (부하 진입점)
K6_SCRIPT="../order-flash-sale.js"
RESULTS="results"
COMPOSE="docker compose -f docker-compose.multi.yml"

mkdir -p "$RESULTS"
mysql_exec() { $COMPOSE exec -T mysql mysql -uroot -proot commerce "$@"; }
redis_exec() { $COMPOSE exec -T redis redis-cli "$@"; }

cleanup() { echo "[정리] 컨테이너·볼륨 삭제"; $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; rm -f nginx.conf; }
trap cleanup EXIT

gen_nginx_conf() {   # $1 = 인스턴스 수 -> nginx.conf (upstream 명시 = 라운드로빈 균등)
  local n=$1 servers="" i
  for i in $(seq 1 "$n"); do servers="${servers}    server app${i}:8080;\n"; done
  printf 'worker_processes auto;\nevents { worker_connections 8192; }\nhttp {\n  access_log off;\n  upstream app_pool {\n%b    keepalive 64;\n  }\n  server {\n    listen 80;\n    location / {\n      proxy_pass http://app_pool;\n      proxy_http_version 1.1;\n      proxy_set_header Connection "";\n    }\n  }\n}\n' "$servers" > nginx.conf
}

wait_healthy() {   # $1 = 서비스명
  local cid _
  for _ in $(seq 1 60); do
    cid=$($COMPOSE ps -q "$1" 2>/dev/null || true)
    [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || true)" = "healthy" ] && return 0
    sleep 1
  done
  echo "[$1] healthy 대기 실패"; $COMPOSE logs "$1" 2>&1 | tail -20; return 1
}

wait_app() {   # $1 = 호스트 포트 (app 개별 UP 확인)
  local _
  for _ in $(seq 1 90); do
    curl -sf "http://localhost:$1/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && return 0
    sleep 2
  done
  echo "[app:$1] UP 대기 실패"; return 1
}

start_apps() {   # $1=STRATEGY $2=N — app1..N + nginx 재기동, 개별 UP 대기
  local i apps=""
  gen_nginx_conf "$2"
  for i in $(seq 1 "$2"); do apps="$apps app$i"; done
  # shellcheck disable=SC2086
  STRATEGY=$1 $COMPOSE up -d --force-recreate $apps >/dev/null
  for i in $(seq 1 "$2"); do wait_app "1808$i"; done
  STRATEGY=$1 $COMPOSE up -d --force-recreate nginx >/dev/null   # 생성된 upstream 반영
  sleep 1
}

reset_stock() {   # $1 = STOCK — HotDealStock(경합 대상) 및 부수 재고를 넉넉히 세팅, 주문 초기화
  mysql_exec -e "UPDATE hot_deal_stock SET remaining_quantity=$1 WHERE hot_deal_id=1; \
    UPDATE hot_deals SET total_quantity=$1 WHERE id=1; \
    UPDATE product_stock SET on_hand_quantity=$1, reserved_quantity=$1 WHERE product_id=1; \
    DELETE FROM orders;"
  redis_exec SET hotdeal:stock:1 "$1" >/dev/null
}

snapshot_conn() {   # $1=N $2=logfile — 인스턴스별 HikariCP active 합산 vs DB Threads
  local i v h=0 tc tr
  for i in $(seq 1 "$1"); do
    v=$(curl -s "http://localhost:1808$i/actuator/prometheus" 2>/dev/null | grep '^hikaricp_connections_active' | awk '{print $2}' | cut -d. -f1)
    h=$((h + ${v:-0}))
  done
  tc=$(mysql_exec -N -e "SHOW STATUS LIKE 'Threads_connected';" 2>/dev/null | awk '{print $2}')
  tr=$(mysql_exec -N -e "SHOW STATUS LIKE 'Threads_running';" 2>/dev/null | awk '{print $2}')
  echo "t=$(date +%s) hikari_active_sum=${h} db_threads_connected=${tc:-?} db_threads_running=${tr:-?}" >> "$2"
}

parse_result() {   # $1 = k6 로그 -> SUCCESS P95 AVG
  SUCCESS=$(grep 'order_success' "$1" | sed -E 's/.*: +([0-9]+).*/\1/' | head -1 || echo "?")
  P95=$(grep 'order_duration' "$1" | grep -oE 'p\(95\)=[0-9.]+(ms|s)' | head -1 | cut -d= -f2 || echo "?")
  AVG=$(grep 'order_duration' "$1" | grep -oE 'avg=[0-9.]+(ms|s)' | head -1 | cut -d= -f2 || echo "?")
}

check_oversell() {   # $1=STRATEGY $2=STOCK -> "0" 또는 "위반(...)"
  local sold left sum
  sold=$(mysql_exec -N -e "SELECT COALESCE(SUM(quantity),0) FROM orders;" 2>/dev/null | tr -d '[:space:]')
  if [ "$1" = "redis" ]; then
    left=$(redis_exec GET hotdeal:stock:1 2>/dev/null | tr -d '[:space:]')
  else
    left=$(mysql_exec -N -e "SELECT remaining_quantity FROM hot_deal_stock WHERE hot_deal_id=1;" 2>/dev/null | tr -d '[:space:]')
  fi
  sum=$(( ${left:-0} + ${sold:-0} ))
  [ "$sum" = "$2" ] && echo "0" || echo "위반(합=$sum≠$2)"
}

echo "[1/5] 일회용 인프라 기동 (mysql:13306, redis:16379, tmpfs)"
$COMPOSE up -d mysql redis >/dev/null
wait_healthy mysql
wait_healthy redis
echo "  인프라 healthy"

echo "[2/5] 앱 이미지 빌드 (캐시)"
$COMPOSE build app1 >/dev/null

echo "[3/5] 스키마·시드 (app1 첫 기동으로 Flyway 마이그레이션)"
gen_nginx_conf 1
STRATEGY=conditional $COMPOSE up -d app1 >/dev/null
wait_app 18081
mysql_exec < seed.sql
echo "  시드 완료"

echo "[4/5] 측정 A — 처리량/역전 (VU 스윕, 재고=VU×2 전원차감)"
declare -a THR
for STRATEGY in $STRATEGIES; do
  for N in $INSTANCES; do
    start_apps "$STRATEGY" "$N"
    for VU in $VUS; do
      STOCK=$((VU * 2))
      reset_stock "$STOCK"
      TAG="thr-${STRATEGY}-i${N}-vu${VU}"
      OUT="$RESULTS/$TAG.log"
      if [ "$N" = "3" ] && [ "$VU" = "$CONN_VU" ]; then
        CONN="$RESULTS/conn-${STRATEGY}-i${N}-vu${VU}.log"; : > "$CONN"
        k6 run -e ACCOUNTS="$VU" -e PRODUCT_ID=1 -e BASE_URL="$BASE_URL" "$K6_SCRIPT" > "$OUT" 2>&1 &
        KPID=$!
        while kill -0 "$KPID" 2>/dev/null; do snapshot_conn "$N" "$CONN"; sleep 2; done
        wait "$KPID" || true
      else
        k6 run -e ACCOUNTS="$VU" -e PRODUCT_ID=1 -e BASE_URL="$BASE_URL" "$K6_SCRIPT" > "$OUT" 2>&1 || true
      fi
      parse_result "$OUT"
      OV=$(check_oversell "$STRATEGY" "$STOCK")
      THR+=("$STRATEGY|$N|$VU|$SUCCESS|$OV|$P95|$AVG")
      echo "  [$TAG] 성공=$SUCCESS/$VU 오버셀=$OV p95=$P95 avg=$AVG"
    done
  done
done

echo "[5/5] 측정 B — 오버셀0 (재고 $OVERSELL_STOCK < 인원 $OVERSELL_VU, 인스턴스 3)"
declare -a OVR
for STRATEGY in $STRATEGIES; do
  start_apps "$STRATEGY" 3
  reset_stock "$OVERSELL_STOCK"
  OUT="$RESULTS/oversell-${STRATEGY}-i3-vu${OVERSELL_VU}.log"
  k6 run -e ACCOUNTS="$OVERSELL_VU" -e PRODUCT_ID=1 -e BASE_URL="$BASE_URL" "$K6_SCRIPT" > "$OUT" 2>&1 || true
  parse_result "$OUT"
  OV=$(check_oversell "$STRATEGY" "$OVERSELL_STOCK")
  OVR+=("$STRATEGY|3|$OVERSELL_VU|$SUCCESS|$OV|$P95")
  echo "  [oversell-$STRATEGY] 성공=$SUCCESS (재고 $OVERSELL_STOCK) 오버셀=$OV p95=$P95"
done

echo ""
echo "======== 측정 A: 처리량/역전 (재고=VU×2 전원차감) ========"
printf "%-12s %-5s %-6s %-10s %-14s %-9s %-9s\n" 전략 인스 VU 성공 오버셀 p95 avg
for r in "${THR[@]}"; do IFS='|' read -r s n vu su ov p a <<<"$r"; printf "%-12s %-5s %-6s %-10s %-14s %-9s %-9s\n" "$s" "$n" "$vu" "$su" "$ov" "$p" "$a"; done

echo ""
echo "======== 측정 B: 오버셀0 (재고 $OVERSELL_STOCK, 품절 경합) ========"
printf "%-12s %-5s %-6s %-10s %-14s %-9s\n" 전략 인스 VU 성공 오버셀 p95
for r in "${OVR[@]}"; do IFS='|' read -r s n vu su ov p <<<"$r"; printf "%-12s %-5s %-6s %-10s %-14s %-9s\n" "$s" "$n" "$vu" "$su" "$ov" "$p"; done

echo ""
echo "======== 측정 C: 커넥션 총량 (인스턴스 3 · VU $CONN_VU 부하 중 스냅샷) ========"
for f in "$RESULTS"/conn-*.log; do
  [ -f "$f" ] || continue
  echo "-- $f (peak) --"
  sort -t= -k3 -n "$f" | tail -1
done
