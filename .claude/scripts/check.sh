#!/usr/bin/env bash
# 계약 위반을 기계로 검출한다. 검사 로직의 유일한 소재 — 훅은 이 스크립트를 부르기만 한다.
#   --pre-edit <경로>  편집하기 전에 막아야 할 것만 (PreToolUse 훅용)
#   --file <경로>      편집 결과 검사 (PostToolUse 훅용)
#   --all              저장소 전량 (리뷰·PR 전)
# 종료 코드: 0 통과 / 2 위반 / 3 전제 조건 실패
set -uo pipefail

die() { printf '[check.sh] 실행 불가: %s\n' "$1" >&2; exit 3; }

command -v git >/dev/null 2>&1 || die "git 을 찾을 수 없다"
REPO=$(git rev-parse --show-toplevel 2>/dev/null) || die "git 저장소 안이 아니다"
cd "$REPO" || die "저장소 루트로 이동 실패"

TAB=$(printf '\t')
violations=0

report() {
  violations=$((violations + 1))
  printf '  %s\n    %s\n' "$1" "$2" >&2
}

# 오탐이 구조적으로 0인 검사만 둔다.
# 들여쓰기 깊이(2칸 vs 4칸)는 중첩 구조의 함수라 줄 단위로 판별할 수 없다 — 탭만 본다.
check_java() {
  local f="$1" hit

  hit=$(grep -n "^${TAB}" "$f" 2>/dev/null | head -3)
  [ -n "$hit" ] && report "$f — 탭 들여쓰기" "$(echo "$hit" | head -1). 이 저장소는 공백 2칸(.editorconfig)"

  case "$f" in
    */controller/*)
      hit=$(grep -n 'return ApiResponse\.' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — 응답 이중 래핑" "$hit. ApiResponseAdvice 가 감싸므로 raw DTO 를 반환한다"

      hit=$(grep -n '^import .*\.repository\.' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — 계층 위반" "$hit. Controller 는 Facade 만 호출한다"
      ;;
    */entity/*)
      local jc nc
      jc=$(grep -c '@JoinColumn' "$f" 2>/dev/null || true)
      nc=$(grep -c 'NO_CONSTRAINT' "$f" 2>/dev/null || true)
      [ "${jc:-0}" -gt "${nc:-0}" ] && report "$f — FK 제약 (@JoinColumn ${jc}개 중 ${nc}개만 명시)" \
        "foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT) 누락. 근거 docs/adr/0003-no-db-fk-constraints.md"
      ;;
  esac

  # 도메인 로직에만 적용한다. global/ 의 설정·보안 가드는 기동 시점 fail-fast 라
  # HttpStatus 를 들고 다니는 DomainException 이 맞지 않는다 (예: JwtKeyHolder 의 키 길이 검증).
  case "$f" in
    src/main/java/*/domain/*)
      hit=$(grep -nE 'throw new (IllegalStateException|IllegalArgumentException)' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — 예외 체계" "$hit. DomainException({Domain}ExceptionCode.X) 를 쓴다"
      ;;
  esac
}

# 이미 적용된 마이그레이션을 고치면 체크섬이 어긋나 통합 테스트가 전부 죽는다.
# git 에 이미 있는 파일 = 적용된 것으로 본다.
check_migration() {
  local f="$1"
  git ls-files --error-unmatch "$f" >/dev/null 2>&1 || return 0
  report "$f — 적용된 마이그레이션 수정" \
    "Flyway 체크섬이 어긋나 통합 테스트가 전부 실패한다. 새 V{n} 파일을 추가한다"
}

dispatch() {
  local f="$1"
  [ -f "$f" ] || return 0
  case "$f" in
    */db/migration/V*.sql) check_migration "$f" ;;
    *.java)                check_java "$f" ;;
  esac
}

# @Transactional 누락은 인터페이스·추상 클래스·DB 를 쓰지 않는 구현체와
# grep 으로 구별할 수 없다. 차단하지 않고 --all 에서만 눈으로 볼 목록을 낸다.
advisories() {
  local out
  out=$(find src/main/java -path '*/service/*.java' 2>/dev/null | while read -r f; do
    grep -q 'interface \|abstract class ' "$f" && continue
    grep -q '@Transactional' "$f" || echo "  $f"
  done)
  if [ -n "$out" ]; then
    printf '\n[참고] Service 에 @Transactional 이 없다 — 정당할 수 있다(DB 미사용 구현체 등). 판단은 사람이 한다.\n%s\n' "$out"
  fi
}

rel() {
  local t="$1"
  case "$t" in "$REPO"/*) printf '%s' "${t#"$REPO"/}" ;; *) printf '%s' "$t" ;; esac
}

case "${1:-}" in
  # 편집 후에 잡으면 파일이 이미 망가진 뒤다. 되돌릴 수 없는 것만 미리 막는다.
  --pre-edit)
    [ $# -ge 2 ] || die "--pre-edit 에 경로가 없다"
    target=$(rel "$2")
    case "$target" in
      */db/migration/V*.sql) check_migration "$target" ;;
    esac
    ;;
  --file)
    [ $# -ge 2 ] || die "--file 에 경로가 없다"
    target=$(rel "$2")
    dispatch "$target"
    ;;
  --all)
    while IFS= read -r f; do dispatch "$f"; done < <(git ls-files '*.java')
    advisories
    ;;
  *)
    die "사용법: check.sh --pre-edit <경로> | --file <경로> | --all"
    ;;
esac

if [ "$violations" -gt 0 ]; then
  printf '\n이 검사가 이 파일에 대해 틀렸다고 판단되면, 검사를 만족시키려고 코드를 바꾸지 말고\n왜 예외인지 사용자에게 한 줄로 보고하고 진행한다.\n' >&2
  exit 2
fi

[ "${1:-}" = "--all" ] && printf '[check.sh] 위반 0건\n'
exit 0
