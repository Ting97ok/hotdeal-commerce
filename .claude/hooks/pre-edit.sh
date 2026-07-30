#!/usr/bin/env bash
# PreToolUse(Write|Edit) — 편집 후에 잡으면 이미 늦는 것만 막는다.
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

command -v python3 >/dev/null 2>&1 || { echo "[pre-edit] python3 없음 — 훅이 검사를 못 한다" >&2; exit 3; }

path=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' 2>/dev/null) \
  || { echo "[pre-edit] 훅 입력 JSON 파싱 실패 — 검사를 건너뛰었다" >&2; exit 3; }
[ -n "$path" ] && exec "$DIR/../scripts/check.sh" --pre-edit "$path"
exit 0
