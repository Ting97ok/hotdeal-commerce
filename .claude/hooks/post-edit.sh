#!/usr/bin/env bash
# PostToolUse(Write|Edit) — 편집 결과를 검사한다. 위반이면 종료 코드 2 로 되먹인다.
#   1. check.sh --file (Java·테스트·SQL·docs 문서·README)
#   2. src/main 을 고쳤는데 작업 트리에 src/test 변경이 없으면 참고로 되먹인다 (전역 「TDD」: RED 가 먼저다). 막지 않는다
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

command -v python3 >/dev/null 2>&1 || { echo "[post-edit] python3 없음 — 훅이 검사를 못 한다" >&2; exit 3; }

path=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' 2>/dev/null) \
  || { echo "[post-edit] 훅 입력 JSON 파싱 실패 — 검사를 건너뛰었다" >&2; exit 3; }
[ -n "$path" ] || exit 0

"$DIR/../scripts/check.sh" --file "$path"
rc=$?
[ "$rc" -ne 0 ] && exit "$rc"

case "$path" in
  *src/main/java/*.java)
    if ! git -C "$DIR/../.." status --porcelain 2>/dev/null | grep -q 'src/test/'; then
      printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"[TDD 참고] src/main 을 고쳤는데 작업 트리에 src/test 변경이 없다. 실패 테스트(RED)가 먼저인지 본다. 리팩터·문서 반영 단계면 그대로 진행한다."}}\n'
    fi
    ;;
esac
exit 0
