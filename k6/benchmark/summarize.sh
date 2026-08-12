#!/usr/bin/env bash
# raw.tsv 회차 기록을 마크다운 표로 접는다. 같은 조건의 여러 회차는 한 행이 되고
# 값은 「중앙값 (최소~최대)」로 적는다. n=3 에서 표준편차는 있는 체하는 숫자다.
#
# 사용: bash summarize.sh sweep|disk|mix   (run-multi.sh 가 끝에 호출한다)
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-sweep}"
RAW="results/$MODE/raw.tsv"
[ -f "$RAW" ] || { echo "$RAW 이 없다. run-multi.sh 를 먼저 돌릴 것"; exit 1; }

# raw.tsv 열: 1전략 2인스턴스 3VU 4변형 5회차 6성공 7오버셀 8p95ms 9avgms 10처리율 11락횟수 12락ms 13조회p95ms
FOLD='
function med(src, n,   a, i, j, t) {
  for (i = 1; i <= n; i++) a[i] = src[i]
  for (i = 1; i < n; i++) for (j = i + 1; j <= n; j++) if (a[j] < a[i]) { t = a[i]; a[i] = a[j]; a[j] = t }
  return (n % 2) ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2
}
function lo(src, n,   i, m) { m = src[1]; for (i = 2; i <= n; i++) if (src[i] < m) m = src[i]; return m }
function hi(src, n,   i, m) { m = src[1]; for (i = 2; i <= n; i++) if (src[i] > m) m = src[i]; return m }
function dur(v) { return (v == "" ? "-" : (v + 0 >= 1000 ? sprintf("%.2f초", v / 1000) : sprintf("%.0fms", v))) }
function span(src, n) {
  if (n == 0) return "-"
  if (n == 1) return dur(src[1])
  return sprintf("%s (%s~%s)", dur(med(src, n)), dur(lo(src, n)), dur(hi(src, n)))
}
function num(src, n,   v) { if (n == 0) return "-"; v = med(src, n); return (v >= 100 ? sprintf("%.0f", v) : sprintf("%.1f", v)) }
# 회차마다 같으면 값 하나, 갈리면 최소~최대. 중앙값으로 접으면 어느 회차가
# 몇 건을 흘렸는지가 사라진다 — 건수는 지연과 달리 접으면 안 되는 값이다.
function rng(src, n,   l, h) { if (n == 0) return "-"; l = lo(src, n); h = hi(src, n); return (l == h ? sprintf("%d", l) : sprintf("%d~%d", l, h)) }
'

# 조건별로 회차 값을 모아 한 행으로 접는다. 정렬키를 앞에 붙여 내고 sort 로 순서를 잡는다.
fold_rows() {
  awk -F'\t' "$FOLD"'
  /^#/ { next }
  {
    key = $1 "|" $2 "|" $3 "|" $4
    if (!(key in seen)) { seen[key] = 1; order[++nk] = key }
    c = ++n[key]
    p95[key, c] = $8; rate[key, c] = $10; lw[key, c] = $11; lt[key, c] = $12; lk[key, c] = $13
    suc[key, c] = $6; fal[key, c] = ($14 == "" ? 0 : $14); aq[key, c] = ($15 == "" ? 0 : $15); vu[key] = $3
    if ($7 != "0" && $7 != "-") bad[key] = bad[key] " " $7
  }
  END {
    for (i = 1; i <= nk; i++) {
      key = order[i]; split(key, f, "|")
      cnt = n[key]
      for (j = 1; j <= cnt; j++) { P[j] = p95[key, j]; R[j] = rate[key, j]; W[j] = lw[key, j]; T[j] = lt[key, j]; S[j] = suc[key, j]; F[j] = fal[key, j]; Q[j] = aq[key, j] }
      lkn = 0
      for (j = 1; j <= cnt; j++) if (lk[key, j] != "") L[++lkn] = lk[key, j]
      ov = (key in bad) ? "위반" bad[key] : "0"
      sk = sprintf("%s-%02d-%06d-%s", f[1], f[2], f[3], f[4])
      printf "%s\t| %s | %s | %s | %s | %s/s | %s/%s | %s | %s | %s회 / %s | %s |\n", sk,
        f[1], f[2], f[3], span(P, cnt), num(R, cnt), rng(S, cnt), vu[key], rng(F, cnt), ov, num(W, cnt), dur(med(T, cnt)), dur(med(Q, cnt))
      if (lkn > 0) lookup[sk] = sprintf("%s\t| %s | %s | %s | %s | %s |\n", sk, f[1], f[2], f[3], f[4], span(L, lkn))
    }
  }' "$RAW" | sort | cut -f2-
}

fold_lookup() {
  awk -F'\t' "$FOLD"'
  /^#/ { next }
  $13 != "" {
    key = $1 "|" $2 "|" $3 "|" $4
    if (!(key in seen)) { seen[key] = 1; order[++nk] = key }
    lk[key, ++n[key]] = $13
  }
  END {
    for (i = 1; i <= nk; i++) {
      key = order[i]; split(key, f, "|"); cnt = n[key]
      for (j = 1; j <= cnt; j++) L[j] = lk[key, j]
      sk = sprintf("%s-%02d-%06d-%s", f[1], f[2], f[3], f[4])
      printf "%s\t| %s | %s | %s | %s | %s |\n", sk, f[1], f[2], f[3], (f[4] == "base" ? "없음" : "주문 폭주"), span(L, cnt)
    }
  }' "$RAW" | sort | cut -f2-
}

ROUNDS_SEEN=$(grep -v '^#' "$RAW" | cut -f5 | sort -u | wc -l | tr -d '[:space:]')
STORAGE=$([ "$MODE" = "disk" ] && echo "디스크 (named volume)" || echo "tmpfs")

echo "# 벤치마크 요약 — $MODE"
echo
echo "\`run-multi.sh\` 가 생성한다. 손으로 고치지 않는다 — 재실행하면 덮인다."
echo
echo "- 스토리지 **$STORAGE** · 재고 = VU×2 (전원 차감) · 회차 **${ROUNDS_SEEN}회**"
echo "- 값은 **중앙값 (최소~최대)**. 회차별 원값은 [\`results/$MODE/raw.tsv\`](./$MODE/raw.tsv) 한 줄씩"
echo "- 회차별 k6 출력 원문은 저장소에 두지 않는다. 수치가 전부 raw.tsv 에 있고, 원문은 재실행하면 다시 나온다"
echo "- 주문 p95 는 \`order_duration\` — 주문 요청만 잰다. 처리율은 \`order_success\` 의 초당 건수"
echo

case "$MODE" in
  mix)
    echo "## 배경 조회 지연 — 재고 부하가 번지는가"
    echo
    echo "\`GET /api/auth/me\` (users PK 단건). 같은 도착률·같은 지속 시간으로 두 번 돌려, 주문 폭주가 겹칠 때만 달라지는지 본다."
    echo
    echo "| 전략 | 앱 | VU | 동시 부하 | 조회 p95 |"
    echo "|---|:--:|--:|---|---|"
    fold_lookup
    echo
    echo "## 주문 지연 (참고)"
    echo
    echo "> 이 모드의 **처리율은 읽지 않는다.** 배경 조회의 지속 시간이 k6 총 실행 시간에 들어가"
    echo "> 분모가 되므로 주문 처리율이 실제보다 훨씬 낮게 나온다. 처리율은 sweep 요약에서 본다."
    echo "> \`base\` 행은 주문을 쏘지 않는 기준선이라 성공이 0 이다."
    ;;
  disk) echo "## 디스크 스토리지 — 커밋 저장 비용이 들어간 값" ;;
  *)    echo "## 인원 스윕 — SLA 가 깨지는 지점" ;;
esac

echo
echo "| 전략 | 앱 | VU | 주문 p95 | 처리율 | 성공 | 전송 실패 | 오버셀 | 행잠금 대기 | 커넥션 획득 대기 |"
echo "|---|:--:|--:|---|---|---|---|---|---|---|"
fold_rows
echo
echo "**전송 실패**는 200·409 가 아닌 응답이다 — 연결이 끊기거나 5xx 일 때만 오른다."
echo "성공 건수가 인원에 못 미치는 것이 경합 때문인지 요청이 서버에 닿지도 못한 것인지를 가른다."
echo "성공·실패는 중앙값으로 접지 않고 회차 간 최소~최대로 적는다."
echo
echo "**커넥션 획득 대기**는 \`hikaricp_connections_acquire_seconds\` 의 부하 전후 누적 델타(앱 합산)다."
echo "풀이 병목이면 여기가 오르고, 자리가 남으면 0 에 가깝다. 순간값(active)은 짧고 잦은 대기를 놓치므로 누적으로 잰다."

if [ -f "results/$MODE/oversell.tsv" ]; then
  echo
  echo "## 품절 경합 — 재고 10 에 1,000명"
  echo
  echo "| 전략 | 회차 | 성공 | 오버셀 | p95 |"
  echo "|---|:--:|---|---|---|"
  awk -F'\t' "$FOLD"'{ printf "| %s | r%s | %s | %s | %s |\n", $1, $2, $3, $4, dur($5) }' "results/$MODE/oversell.tsv"
fi

if ls "results/$MODE"/conn-*.log >/dev/null 2>&1; then
  echo
  echo "## 커넥션 총량 — 부하 중 스냅샷의 최대"
  echo
  echo "| 조합 | 피크 |"
  echo "|---|---|"
  for f in "results/$MODE"/conn-*.log; do
    printf '| `%s` | %s |\n' "$(basename "$f")" "$(sort -t= -k3 -n "$f" | tail -1)"
  done
fi
