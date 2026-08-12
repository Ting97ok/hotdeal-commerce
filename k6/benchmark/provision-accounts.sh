#!/usr/bin/env bash
# 벤치마크 계정 선행 생성 — 회원가입·로그인을 병렬로 끝내고 액세스 토큰을 JSON 배열로 남긴다.
#
# k6 의 setup() 에서 하던 일이다. 옮긴 이유는 지표 때문이다. setup 은 k6 총 실행
# 시간에 들어가는데 iterations/s 의 분모가 그 총 실행 시간이라, 계정 1,000개를
# 만드는 2분이 처리율을 7건/초로 만든다. 실제 주문 부하는 2초다.
# 게다가 계정이 DB 에 남으므로 뒤에 도는 전략은 회원가입이 중복 거절로 즉시 떨어져,
# 먼저 도는 전략만 생성 비용을 뒤집어쓰는 실행 순서 효과까지 있었다.
#
# 사용:
#   bash provision-accounts.sh 3000                        (기본 URL·출력)
#   BASE_URL=http://localhost:18080 bash provision-accounts.sh 1000 results/tokens.json
set -euo pipefail
cd "$(dirname "$0")"

COUNT="${1:-1000}"
OUT="${2:-results/tokens.json}"
BASE_URL="${BASE_URL:-http://localhost:18080}"
PARALLEL="${PARALLEL:-40}"
PASSWORD="password123"

mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

# 계정 하나: 가입(이미 있으면 거절되고 그대로 진행) -> 로그인 -> "인덱스<TAB>토큰"
# 어떤 경우에도 0 으로 끝낸다. 비영으로 끝나면 xargs 가 123 을 반환하고 pipefail 이
# 스크립트 전체를 조용히 죽인다 — 못 받은 계정은 아래에서 개수로 판정해 재시도한다.
provision_one() {
  # 한 local 문에서 앞 변수를 뒤에서 참조하지 않는다. bash 3.2(macOS 기본)는 같은
  # 선언 안의 i 를 아직 못 봐서 email 이 전부 bench@test.com 하나가 된다.
  local i=$1
  local email="bench${i}@test.com"
  local tok
  curl -s -o /dev/null -m 30 -X POST "$BASE_URL/api/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\",\"name\":\"bench$i\"}" >/dev/null 2>&1 || true
  tok=$(curl -s -m 30 -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}" 2>/dev/null \
    | jq -r '.data.accessToken // empty' 2>/dev/null) || true
  [ -n "${tok:-}" ] && printf '%s\t%s\n' "$i" "$tok"
  return 0
}
export -f provision_one
export BASE_URL PASSWORD

echo "[프로비저닝] 계정 $COUNT 개 · 병렬 $PARALLEL · $BASE_URL"
START=$(date +%s)
seq 0 $((COUNT - 1)) | xargs -P "$PARALLEL" -I{} bash -c 'provision_one "$@"' _ {} >> "$TMP" || true

# 못 받은 인덱스만 두 번 더 훑는다. 앱이 막 뜬 직후에는 몇 건이 흘리게 된다.
for attempt in 1 2; do
  MISSING=$(comm -23 <(seq 0 $((COUNT - 1)) | sort) <(cut -f1 "$TMP" | sort) | sort -n)
  [ -z "$MISSING" ] && break
  echo "  재시도 $attempt — $(echo "$MISSING" | wc -l | tr -d '[:space:]') 건"
  echo "$MISSING" | xargs -P "$PARALLEL" -I{} bash -c 'provision_one "$@"' _ {} >> "$TMP" || true
done

# 인덱스 순으로 정렬해 배열에 담는다. 순서가 고정돼야 VU 별 계정 배정이 회차마다 같다.
GOT=$(cut -f1 "$TMP" | sort -u | wc -l | tr -d '[:space:]')
if [ "$GOT" != "$COUNT" ]; then
  echo "[실패] 토큰 $GOT/$COUNT 개만 확보했다. 한 건을 다시 쳐 응답을 그대로 남긴다."
  probe() { curl -s -m 10 "$@" 2>&1 | head -c 400; echo; }
  echo -n "  health: "; probe "$BASE_URL/actuator/health"
  echo -n "  signup: "; probe -X POST "$BASE_URL/api/auth/signup" -H 'Content-Type: application/json' -d "{\"email\":\"probe@test.com\",\"password\":\"$PASSWORD\",\"name\":\"probe\"}"
  echo -n "  login : "; probe -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"probe@test.com\",\"password\":\"$PASSWORD\"}"
  exit 1
fi
sort -n -u -k1,1 "$TMP" | cut -f2 | jq -R . | jq -s . > "$OUT"

# 토큰이 서로 달라야 1인 1주문 유니크를 통과한다. 겹치면 계정 하나로 몰린 것이고,
# 그 상태로 측정하면 대부분이 중복 주문으로 거절되면서 경합을 잰 것처럼 보인다.
UNIQ=$(jq -r '.[]' "$OUT" | sort -u | wc -l | tr -d '[:space:]')
if [ "$UNIQ" != "$COUNT" ]; then
  echo "[실패] 토큰 $COUNT 개 중 고유한 것이 $UNIQ 개뿐이다 — 계정이 제대로 갈리지 않았다"; exit 1
fi

echo "[프로비저닝] 완료 — 고유 토큰 $GOT 개 -> $OUT ($(($(date +%s) - START))초)"
