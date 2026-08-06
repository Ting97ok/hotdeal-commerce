#!/usr/bin/env bash
# 다중 인스턴스 동시성 벤치마크 — nginx LB 뒤 app 1 vs 3, conditional·redis 전략.
# 단일 스택(run.sh)과 격리. 같은 조건을 ROUNDS 회 반복해 편차를 남긴다.
# raw 로그는 results/{모드}/ 에 조합·회차별 전량 보존하고, 요약은 마크다운 표로 낸다.
#
# 모드 셋
#   sweep  인원을 늘려가며 SLA 가 깨지는 지점을 찾는다 (+ 오버셀0 · 커넥션 총량)
#   disk   MySQL 데이터를 tmpfs 가 아니라 디스크에 두고 커밋 저장 비용을 넣는다
#   mix    주문 폭주와 무관한 조회를 같은 시간에 흘려 재고 부하가 번지는지 본다
#
# 사용:
#   bash k6/benchmark/run-multi.sh                            (스윕 전량)
#   MODE=disk bash k6/benchmark/run-multi.sh
#   MODE=mix  bash k6/benchmark/run-multi.sh
#   VUS="1000" ROUNDS=1 STRATEGIES="conditional" bash k6/benchmark/run-multi.sh   (한 점만)
set -euo pipefail
cd "$(dirname "$0")"

MODE="${MODE:-sweep}"
ROUNDS="${ROUNDS:-3}"
STRATEGIES="${STRATEGIES:-conditional redis}"

case "$MODE" in
  sweep) INSTANCES="${INSTANCES:-1 3}"; VUS="${VUS:-100 250 500 1000 2000 3000}" ;;
  disk)  INSTANCES="${INSTANCES:-3}";   VUS="${VUS:-1000}"
         # compose 의 마운트 대상을 맞바꿔 데이터 디렉터리를 named volume 으로 돌린다.
         export MYSQL_TMPFS_TARGET=/var/lib/mysql-unused MYSQL_DISK_TARGET=/var/lib/mysql ;;
  mix)   INSTANCES="${INSTANCES:-3}";   VUS="${VUS:-1000}" ;;
  *) echo "MODE 는 sweep|disk|mix"; exit 1 ;;
esac

OVERSELL_STOCK="${OVERSELL_STOCK:-10}"
OVERSELL_VU="${OVERSELL_VU:-1000}"
# 커넥션 폴링을 붙일 대표 VU (인스턴스 3 에서만). 스윕의 최대 인원에 붙인다 —
# 계정 발급을 밖으로 뺀 뒤로 부하 구간이 인원에 비례해 짧아져서, 작은 인원에서는
# 스냅샷이 두어 개밖에 안 찍혀 포화를 볼 수 없다.
CONN_VU="${CONN_VU:-3000}"
MIX_RATE="${MIX_RATE:-50}"
MIX_DURATION="${MIX_DURATION:-8s}"
WARMUP_VU="${WARMUP_VU:-200}"                 # 조합마다 한 번 버리는 예열 회차의 인원
BASE_URL="http://localhost:18080"             # nginx LB (부하 진입점)
K6_SCRIPT="../order-flash-sale.js"
RESULTS="results/$MODE"
TOKENS="$PWD/results/tokens.json"
RAW="$RESULTS/raw.tsv"
SUMMARY="results/summary-$MODE.md"
COMPOSE="docker compose -f docker-compose.multi.yml"

# 이 모드의 옛 결과를 통째로 지우고 시작한다. 회차 로그는 파일명에 조합·회차가 들어가
# 있어 격자를 바꿔 재실행하면 옛 조합이 그대로 남고, 요약이 어느 실행의 것인지 알 수
# 없게 된다. 옛 회차는 git 이 갖는다.
rm -rf "$RESULTS"
mkdir -p "$RESULTS"
# raw.tsv 가 이 측정의 원본이다. 회차별 .log·.json 은 저장소에 남기지 않으므로
# (재현하면 다시 나온다) 사람이 열었을 때 열이 무엇인지 알 수 있어야 한다.
printf '#전략\t앱\tVU\t변형\t회차\t성공\t오버셀\t주문p95ms\t주문avgms\t처리율\t락횟수\t락누적ms\t조회p95ms\t전송실패\n' > "$RAW"

mysql_exec() { $COMPOSE exec -T mysql mysql -uroot -proot commerce "$@"; }
redis_exec() { $COMPOSE exec -T redis redis-cli "$@"; }
lock_stat() { mysql_exec -N -e "SHOW GLOBAL STATUS LIKE '$1';" 2>/dev/null | awk '{print $2}' | tr -d '[:space:]'; }

cleanup() { echo "[정리] 컨테이너·볼륨 삭제"; $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; rm -f nginx.conf; }
trap cleanup EXIT

gen_nginx_conf() {   # $1 = 인스턴스 수 -> nginx.conf (upstream 명시 = 라운드로빈 균등)
  local n=$1 servers="" i
  for i in $(seq 1 "$n"); do servers="${servers}    server app${i}:8080;\n"; done
  printf 'worker_processes auto;\nevents { worker_connections 8192; }\nhttp {\n  access_log off;\n  upstream app_pool {\n%b    keepalive 64;\n  }\n  server {\n    listen 80;\n    location / {\n      proxy_pass http://app_pool;\n      proxy_http_version 1.1;\n      proxy_set_header Connection "";\n    }\n  }\n}\n' "$servers" > nginx.conf
}

wait_healthy() {   # $1 = 서비스명
  local cid _
  for _ in $(seq 1 90); do
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

snapshot_conn() {   # $1=N $2=logfile — 인스턴스별 HikariCP active 합산 vs DB Threads + 행 잠금 순간 대기
  local i v h=0 tc tr rlw
  for i in $(seq 1 "$1"); do
    v=$(curl -s "http://localhost:1808$i/actuator/prometheus" 2>/dev/null | grep '^hikaricp_connections_active' | awk '{print $2}' | cut -d. -f1)
    h=$((h + ${v:-0}))
  done
  tc=$(mysql_exec -N -e "SHOW STATUS LIKE 'Threads_connected';" 2>/dev/null | awk '{print $2}')
  tr=$(mysql_exec -N -e "SHOW STATUS LIKE 'Threads_running';" 2>/dev/null | awk '{print $2}')
  rlw=$(lock_stat Innodb_row_lock_current_waits)
  echo "t=$(date +%s) hikari_active_sum=${h} db_threads_connected=${tc:-?} db_threads_running=${tr:-?} innodb_row_lock_current_waits=${rlw:-?}" >> "$2"
}

# k6 의 JSON 요약에서 값 하나. 지표가 없으면 빈 문자열 (배경 부하 없는 회차의 lookup 등)
jval() { jq -r "$2 // empty" "$1" 2>/dev/null | head -1; }

parse_summary() {   # $1 = k6 --summary-export JSON -> SUCCESS P95 AVG RATE FAILED LOOKUP_P95 LOOKUP_N
  SUCCESS=$(jval "$1" '.metrics.order_success.count // .metrics.order_success.values.count'); SUCCESS=${SUCCESS:-0}
  # 전송 실패 건수. 200·409 는 정상 응답이라 여기 안 잡히고, 연결이 끊기거나 5xx 일
  # 때만 오른다. 고부하에서 성공 건수가 모자란 것이 경합인지 연결 유실인지 이것으로 갈린다.
  # http_req_failed 는 Rate 라 passes 가 '조건이 참' = 실패한 요청 수다. fails 가 아니다.
  FAILED=$(jval "$1" '.metrics.http_req_failed.passes // .metrics.http_req_failed.values.passes'); FAILED=${FAILED:-0}
  P95=$(jval "$1" '.metrics.order_duration["p(95)"] // .metrics.order_duration.values["p(95)"]')
  AVG=$(jval "$1" '.metrics.order_duration.avg // .metrics.order_duration.values.avg')
  RATE=$(jval "$1" '.metrics.order_success.rate // .metrics.order_success.values.rate')
  LOOKUP_P95=$(jval "$1" '.metrics.lookup_duration["p(95)"] // .metrics.lookup_duration.values["p(95)"]')
  LOOKUP_N=$(jval "$1" '.metrics.lookup_duration.count // .metrics.lookup_duration.values.count'); LOOKUP_N=${LOOKUP_N:-0}
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

# 한 회차 — k6 실행 + 오버셀 판정 + 락 델타. raw.tsv 에 한 줄 남긴다.
# $1=전략 $2=인스턴스 $3=VU $4=회차 $5=변형(base|load|-) $6=추가 k6 -e 인자들
run_round() {
  local st=$1 n=$2 vu=$3 r=$4 variant=$5; shift 5
  local stock=$((vu * 2)) tag out json conn kpid lw0 lt0 lw1 lt1 lwd ltd ov
  tag="${st}-i${n}-vu${vu}"
  [ "$variant" != "-" ] && tag="${tag}-${variant}"
  tag="${tag}-r${r}"
  out="$RESULTS/$tag.log"; json="$RESULTS/$tag.json"

  reset_stock "$stock"
  lw0=$(lock_stat Innodb_row_lock_waits); lt0=$(lock_stat Innodb_row_lock_time)

  if [ "$MODE" = "sweep" ] && [ "$n" = "3" ] && [ "$vu" = "$CONN_VU" ] && [ "$r" = "1" ]; then
    conn="$RESULTS/conn-${st}-i${n}-vu${vu}.log"; : > "$conn"
    k6 run --summary-export="$json" -e ACCOUNTS="$vu" -e PRODUCT_ID=1 -e BASE_URL="$BASE_URL" \
      -e TOKENS_FILE="$TOKENS" "$@" "$K6_SCRIPT" > "$out" 2>&1 &
    kpid=$!
    while kill -0 "$kpid" 2>/dev/null; do snapshot_conn "$n" "$conn"; sleep 0.2; done
    wait "$kpid" || true
  else
    k6 run --summary-export="$json" -e ACCOUNTS="$vu" -e PRODUCT_ID=1 -e BASE_URL="$BASE_URL" \
      -e TOKENS_FILE="$TOKENS" "$@" "$K6_SCRIPT" > "$out" 2>&1 || true
  fi

  lw1=$(lock_stat Innodb_row_lock_waits); lt1=$(lock_stat Innodb_row_lock_time)
  lwd=$(( ${lw1:-0} - ${lw0:-0} )); ltd=$(( ${lt1:-0} - ${lt0:-0} ))
  parse_summary "$json"
  if [ "$variant" = "base" ]; then ov="-"; else ov=$(check_oversell "$st" "$stock"); fi

  # 예열 회차는 값을 버린다. 앱 재기동 직후 첫 회차는 JIT·버퍼풀이 차갑고 뒤 회차보다
  # 몇 배 느려서, 그대로 두면 회차 편차가 경합이 아니라 워밍업을 재게 된다.
  if [ "$variant" = "warmup" ]; then
    echo "  [예열] $st i$n vu$vu — p95=$(fmt_ms "${P95:-}") (버림)"
    return 0
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$st" "$n" "$vu" "$variant" "$r" "$SUCCESS" "$ov" "${P95:-}" "${AVG:-}" "${RATE:-}" "$lwd" "$ltd" "${LOOKUP_P95:-}" "$FAILED" >> "$RAW"
  echo "  [$tag] 성공=$SUCCESS/$vu 전송실패=$FAILED 오버셀=$ov p95=$(fmt_ms "${P95:-}") 처리율=${RATE:-?}/s 조회p95=$(fmt_ms "${LOOKUP_P95:-}") 행잠금=${lwd}회/${ltd}ms"
  LAST_LOCK_TIME=$ltd
}

fmt_ms() {   # 밀리초 -> 사람이 읽는 단위
  awk -v v="${1:-}" 'BEGIN{ if (v=="") { print "-" } else if (v+0 >= 1000) { printf "%.2f초", v/1000 } else { printf "%.0fms", v } }'
}

echo "[1/6] 일회용 인프라 기동 (mysql:13306, redis:16379, 스토리지=$([ "$MODE" = disk ] && echo 디스크 || echo tmpfs))"
$COMPOSE up -d mysql redis >/dev/null
wait_healthy mysql
wait_healthy redis
echo "  인프라 healthy"

echo "[2/6] 앱 이미지 빌드 (캐시)"
$COMPOSE build app1 >/dev/null

echo "[3/6] 스키마·시드 (app1 첫 기동으로 Flyway 마이그레이션)"
gen_nginx_conf 1
# nginx 도 함께 띄운다 — 다음 단계의 계정 발급이 LB(18080)를 거치므로 없으면 전부 실패한다.
STRATEGY=conditional $COMPOSE up -d app1 nginx >/dev/null
wait_app 18081
mysql_exec < seed.sql
echo "  시드 완료"

MAX_VU=$(printf '%s\n' $VUS $OVERSELL_VU $WARMUP_VU | sort -n | tail -1)
echo "[4/6] 계정 선행 발급 ($MAX_VU 개) — k6 측정 구간에서 빼낸다"
BASE_URL="$BASE_URL" bash provision-accounts.sh "$MAX_VU" "$TOKENS"

echo "[5/6] 측정 — 모드 $MODE · 전략[$STRATEGIES] · 인스턴스[$INSTANCES] · VU[$VUS] · $ROUNDS 회"
for STRATEGY in $STRATEGIES; do
  for N in $INSTANCES; do
    start_apps "$STRATEGY" "$N"
    run_round "$STRATEGY" "$N" "$WARMUP_VU" 0 warmup
    # 회차를 VU 루프 바깥에 둔다. 같은 조건을 연달아 세 번 치면 캐시가 더워져
    # 편차가 실제보다 작게 나온다. 사이에 다른 VU 회차가 끼게 한다.
    for R in $(seq 1 "$ROUNDS"); do
      for VU in $VUS; do
        if [ "$MODE" = "mix" ]; then
          run_round "$STRATEGY" "$N" "$VU" "$R" base \
            -e MIX_ONLY=1 -e MIX_RATE="$MIX_RATE" -e MIX_DURATION="$MIX_DURATION"
          run_round "$STRATEGY" "$N" "$VU" "$R" load \
            -e MIX=1 -e MIX_RATE="$MIX_RATE" -e MIX_DURATION="$MIX_DURATION"
        else
          run_round "$STRATEGY" "$N" "$VU" "$R" -
        fi
      done
    done
  done
done

if [ "$MODE" = "sweep" ]; then
  echo "[6/6] 오버셀0 (재고 $OVERSELL_STOCK < 인원 $OVERSELL_VU, 인스턴스 3)"
  : > "$RESULTS/oversell.tsv"
  for STRATEGY in $STRATEGIES; do
    start_apps "$STRATEGY" 3
    run_round "$STRATEGY" 3 "$WARMUP_VU" 0 warmup
    for R in $(seq 1 "$ROUNDS"); do
      reset_stock "$OVERSELL_STOCK"
      OUT="$RESULTS/oversell-${STRATEGY}-i3-vu${OVERSELL_VU}-r${R}.log"
      JSON="$RESULTS/oversell-${STRATEGY}-i3-vu${OVERSELL_VU}-r${R}.json"
      k6 run --summary-export="$JSON" -e ACCOUNTS="$OVERSELL_VU" -e PRODUCT_ID=1 \
        -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS" "$K6_SCRIPT" > "$OUT" 2>&1 || true
      parse_summary "$JSON"
      OV=$(check_oversell "$STRATEGY" "$OVERSELL_STOCK")
      printf '%s\t%s\t%s\t%s\t%s\n' "$STRATEGY" "$R" "$SUCCESS" "$OV" "${P95:-}" >> "$RESULTS/oversell.tsv"
      echo "  [oversell-$STRATEGY-r$R] 성공=$SUCCESS (재고 $OVERSELL_STOCK) 오버셀=$OV p95=$(fmt_ms "${P95:-}")"
    done
  done
else
  echo "[6/6] 오버셀0 측정은 sweep 모드에서만 돈다 — 건너뜀"
fi

echo ""
echo "[요약] $SUMMARY 생성"
bash summarize.sh "$MODE" > "$SUMMARY"
cat "$SUMMARY"
