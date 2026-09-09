#!/usr/bin/env bash
# PreToolUse(Write|Edit) — 편집 후에 잡으면 이미 늦는 것만 막는다.
#   1. 적용된 마이그레이션 수정 (check.sh --pre-edit)
#   2. 이 세션에서 spring-conventions 스킬을 부르기 전의 Java 편집 (CLAUDE.md 머리말)
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

command -v python3 >/dev/null 2>&1 || { echo "[pre-edit] python3 없음 — 훅이 검사를 못 한다" >&2; exit 3; }

read -r path transcript < <(python3 -c '
import json,sys
d=json.load(sys.stdin)
print((d.get("tool_input") or {}).get("file_path",""), d.get("transcript_path",""))' 2>/dev/null) \
  || { echo "[pre-edit] 훅 입력 JSON 파싱 실패 — 검사를 건너뛰었다" >&2; exit 3; }
[ -n "${path:-}" ] || exit 0

case "$path" in
  *src/main/java/*.java|*src/test/java/*.java)
    if [ -n "${transcript:-}" ] && [ -f "$transcript" ] && ! grep -q 'spring-conventions' "$transcript" 2>/dev/null; then
      printf '[pre-edit] 이 세션에서 아직 spring-conventions 스킬을 부르지 않았다. Java 소스를 고치기 전에 Skill 도구로 spring-conventions 를 먼저 호출한다 (CLAUDE.md 머리말, 세션당 한 번).\n' >&2
      exit 2
    fi
    ;;
esac

exec "$DIR/../scripts/check.sh" --pre-edit "$path"
