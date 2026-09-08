#!/usr/bin/env bash
# 계약 위반을 기계로 검출한다. 검사 로직의 유일한 소재 — 훅은 이 스크립트를 부르기만 한다.
#   --pre-edit <경로>  편집하기 전에 막아야 할 것만 (PreToolUse 훅용)
#   --file <경로>      편집 결과 검사 (PostToolUse 훅용). Java·SQL·docs 의 md/html·README
#   --all              저장소 전량 (리뷰·PR 전·git pre-push)
# 종료 코드: 0 통과 / 2 위반 / 3 전제 조건 실패
#
# 오탐이 구조적으로 0인 것만 「위반」이다. 판단이 섞이면 --all 의 [참고] 목록에 둔다.
# 위반 목록 (근거는 CLAUDE.md 「하드룰」·「계층」·「문서」, 전역 CLAUDE.md 「문서 작성」):
#   Java   탭 들여쓰기 · 컨트롤러 이중 래핑 · 컨트롤러의 Repository/Service import · FK 제약 미선언 누락
#          · 도메인의 IllegalState/IllegalArgument · 타 도메인 Repository import · 역방향 의존(hotdeal/product/stock → order/payment)
#          · Facade 밖의 TransactionTemplate · BaseEntity 미상속 · constant/·enums/ 디렉터리
#   Test   통합 테스트 클래스 레벨 @Transactional · jsonPath 가 $.result/$.data/$.error 밖
#   SQL    적용된 마이그레이션 수정 · 새 마이그레이션의 PostgreSQL 방언
#   Docs   죽은 링크 · 죽은 앵커 · 고아 문서 · 금지어(색인·핫패스·결론 상자·대가) · 제목 속 개수
#          · 링크 텍스트의 영어 파일명 · 절 인용에 「N절」 없음 · 페이지 안의 <style>·인라인 <script>
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

# 추적 중인 파일이면 HEAD 대비 추가된 줄만, 아니면 전체. 옛 본문의 위반으로 새 편집을 막지 않는다.
added_lines() {
  local f="$1"
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    git diff -U0 HEAD -- "$f" 2>/dev/null | grep '^+' | grep -v '^+++' | cut -c2-
  else
    cat "$f"
  fi
}

# 들여쓰기 깊이(2칸 vs 4칸)는 중첩 구조의 함수라 줄 단위로 판별할 수 없다 — 탭만 본다.
check_java() {
  local f="$1" hit own

  hit=$(grep -n "^${TAB}" "$f" 2>/dev/null | head -3)
  [ -n "$hit" ] && report "$f — 탭 들여쓰기" "$(echo "$hit" | head -1). 이 저장소는 공백 2칸(.editorconfig)"

  case "$f" in
    */controller/*)
      hit=$(grep -n 'return ApiResponse\.' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — 응답 이중 래핑" "$hit. ApiResponseAdvice 가 감싸므로 raw DTO 를 반환한다"

      hit=$(grep -nE '^import .*\.(repository|service)\.' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — 계층 위반" "$hit. Controller 는 Facade 만 호출한다"
      ;;
    */entity/*)
      local jc nc
      jc=$(grep -c '@JoinColumn' "$f" 2>/dev/null || true)
      nc=$(grep -c 'NO_CONSTRAINT' "$f" 2>/dev/null || true)
      [ "${jc:-0}" -gt "${nc:-0}" ] && report "$f — FK 제약 (@JoinColumn ${jc}개 중 ${nc}개만 명시)" \
        "foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT) 누락. 근거 docs/adr/integrity.md"

      if grep -q '^@Entity' "$f" 2>/dev/null && ! grep -q 'extends BaseEntity' "$f" 2>/dev/null; then
        report "$f — BaseEntity 미상속" "엔티티는 global/entity/BaseEntity 를 상속한다 (CLAUDE.md 하드룰 5)"
      fi
      ;;
  esac

  case "$f" in
    */constant/*|*/enums/*)
      report "$f — enum 모음 디렉터리" "enum 은 쓰는 타입과 같은 자리에 둔다. constant/·enums/ 로 모으지 않는다"
      ;;
  esac

  # 도메인 로직에만 적용한다. global/ 의 설정·보안 가드는 기동 시점 fail-fast 라
  # HttpStatus 를 들고 다니는 DomainException 이 맞지 않는다 (예: JwtKeyHolder 의 키 길이 검증).
  case "$f" in
    src/main/java/*/domain/*)
      hit=$(grep -nE 'throw new (IllegalStateException|IllegalArgumentException)' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — 예외 체계" "$hit. DomainException({Domain}ExceptionCode.X) 를 쓴다"

      # 타 도메인 Repository 를 직접 부르지 않는다. 필요하면 그 도메인 Service 를 Facade 에서 부른다
      own=$(printf '%s' "$f" | sed -E 's|.*/domain/([a-z]+)/.*|\1|')
      hit=$(grep -nE '^import com\.sparta\.msa\.commerce\.domain\.[a-z]+\.repository\.' "$f" 2>/dev/null \
            | grep -vE "domain\.${own}\.repository\." | head -1)
      [ -n "$hit" ] && report "$f — 타 도메인 Repository 직접 참조" "$hit. 그 도메인의 Service 를 Facade 에서 부른다 (CLAUDE.md 「계층」)"

      # 의존은 한 방향: 구매(order·payment) → 카탈로그·재고(hotdeal·product·stock). 역방향 없음
      case "$own" in
        hotdeal|product|stock)
          hit=$(grep -nE '^import com\.sparta\.msa\.commerce\.domain\.(order|payment)\.' "$f" 2>/dev/null | head -1)
          [ -n "$hit" ] && report "$f — 역방향 의존" "$hit. hotdeal·product·stock 은 order·payment 를 참조하지 않는다 (docs/adr/architecture.md)"
          ;;
      esac
      ;;
  esac

  # 트랜잭션을 여는 곳은 Facade 뿐이다. TransactionTemplate 은 Facade 에서만 (빈 정의는 global/config)
  case "$f" in
    */facade/*|*/global/config/*) ;;
    src/main/java/*)
      hit=$(grep -n 'TransactionTemplate' "$f" 2>/dev/null | head -1)
      [ -n "$hit" ] && report "$f — Facade 밖의 TransactionTemplate" "$hit. 트랜잭션을 여는 곳은 Facade 뿐이다 (docs/adr/architecture.md 2절)"
      ;;
  esac
}

check_test() {
  local f="$1" hit
  # 클래스 레벨 @Transactional 은 열 0 에 온다. 메서드 레벨은 들여쓰기가 있다
  if grep -q '@SpringBootTest' "$f" 2>/dev/null; then
    hit=$(grep -n '^@Transactional' "$f" 2>/dev/null | head -1)
    [ -n "$hit" ] && report "$f — 통합 테스트 클래스 레벨 @Transactional" "$hit. mockMvc 이후 repository 검증이 깨진다. 격리는 @BeforeEach 의 deleteAll()"
  fi
  hit=$(grep -nE 'jsonPath\("\$\.' "$f" 2>/dev/null | grep -vE 'jsonPath\("\$\.(result|data|error)' | head -1)
  [ -n "$hit" ] && report "$f — 래핑 밖 단언" "$hit. 단언은 \$.result / \$.data.{필드} / \$.error.code 를 탄다"
}

# 이미 적용된 마이그레이션을 고치면 체크섬이 어긋나 통합 테스트가 전부 죽는다.
# git 에 이미 있는 파일 = 적용된 것으로 본다.
check_migration() {
  local f="$1" hit
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    report "$f — 적용된 마이그레이션 수정" \
      "Flyway 체크섬이 어긋나 통합 테스트가 전부 실패한다. 새 V{n} 파일을 추가한다"
    return
  fi
  # 새 파일은 MySQL 방언이어야 한다. PostgreSQL 에서 흔히 새어 들어오는 것만 본다
  hit=$(grep -nE '\b(BIGSERIAL|SERIAL|RETURNING|ILIKE)\b|::[a-z]+' "$f" 2>/dev/null | head -1)
  [ -n "$hit" ] && report "$f — PostgreSQL 방언" "$hit. 이 저장소는 MySQL 8.4 다"
}

# 문서 한 편 검사 — 이번 편집으로 추가된 줄만. 코드 블록·코드 스팬은 뺀다
check_doc_file() {
  local f="$1" body hit
  body=$(added_lines "$f" | awk '/^```/ { c = !c; next } !c' | sed 's/`[^`]*`//g')
  case "$f" in
    *.html) body=$(printf '%s\n' "$body" | sed -E 's|<code>[^<]*</code>||g; s|<pre[^>]*>.*</pre>||g') ;;
  esac
  [ -z "$body" ] && return 0

  # 금지어 (재작성 계획의 금지어 목록. 조사 「대가」는 단어로만 잡는다)
  hit=$(printf '%s\n' "$body" | grep -nE '색인|핫패스|결론 상자' | head -1)
  [ -n "$hit" ] && report "$f — 금지어" "$hit. 색인→문서 목록, 핫패스→매 요청의 인증 검증 경로, 결론 상자→요약"
  hit=$(printf '%s\n' "$body" | grep -nE '(^|[[:space:]「(])대가([[:space:].,)」를는가로에도]|$)' | head -1)
  [ -n "$hit" ] && report "$f — 금지어 「대가」" "$hit. 「그 대신 잃는 것」으로 쓴다"

  # 제목 속 개수 (「근거 문서 두 편」)
  case "$f" in
    *.md)   hit=$(printf '%s\n' "$body" | grep -nE '^#{1,6} .*(한|두|세|네|다섯|여섯|[0-9]+) ?(편|가지|개|종|건)( |$)' | head -1) ;;
    *.html) hit=$(printf '%s\n' "$body" | grep -nE '<h[1-6][^>]*>[^<]*(한|두|세|네|다섯|여섯|[0-9]+) ?(편|가지|개|종|건)( |<)' | head -1) ;;
  esac
  [ -n "$hit" ] && report "$f — 제목 속 개수" "$hit. 제목에 개수를 넣지 않는다"

  # 링크 텍스트에 영어 파일명 노출
  case "$f" in
    *.md)   hit=$(printf '%s\n' "$body" | grep -nE '\[[^]]*\.(md|html)\]\(' | head -1) ;;
    *.html) hit=$(printf '%s\n' "$body" | grep -nE '<a [^>]*>[^<]*\.(md|html)</a>' | head -1) ;;
  esac
  [ -n "$hit" ] && report "$f — 링크 텍스트의 파일명" "$hit. 링크 텍스트는 한국어 문서 이름으로"

  # 타 문서 절 인용은 「N절」 텍스트 + #sec-N 앵커 (같은 문서 안 목차 링크 href="#sec-N" 은 제외)
  case "$f" in
    *.html)
      hit=$(printf '%s\n' "$body" | grep -oE '<a href="[^"#]+\.html#sec-[0-9]+">[^<]*</a>' \
            | while IFS= read -r a; do
                n=$(printf '%s' "$a" | sed -E 's/.*#sec-([0-9]+)".*/\1/')
                printf '%s' "$a" | grep -q "${n}절" || printf '%s\n' "$a"
              done | head -1)
      [ -n "$hit" ] && report "$f — 절 인용" "$hit. 텍스트에 절 번호를 적는다 (예: 핫딜 ADR 4절)"
      # 공통 CSS·스크립트는 docs/assets 에만. 페이지 안 <style>·인라인 <script> 본문 금지 (CDN 로드 태그는 허용)
      case "$f" in docs/assets/*) ;; *)
        hit=$(printf '%s\n' "$body" | grep -nE '<style[ >]' | head -1)
        [ -n "$hit" ] && report "$f — 페이지 안 <style>" "$hit. 공통 CSS 는 docs/assets/ 에 두고 링크한다"
        hit=$(printf '%s\n' "$body" | grep -nE '<script(>| [^>]*>)[^<]+' | grep -v 'src=' | head -1)
        [ -n "$hit" ] && report "$f — 인라인 <script> 본문" "$hit. 공통 스크립트는 docs/assets/ 에 둔다"
        ;;
      esac
      ;;
  esac
}

dispatch() {
  local f="$1"
  [ -f "$f" ] || return 0
  case "$f" in
    */db/migration/V*.sql)  check_migration "$f" ;;
    src/test/java/*.java)   check_test "$f" ;;
    *.java)                 check_java "$f" ;;
    docs/*.md|docs/*.html|README.md) check_doc_file "$f" ;;
  esac
}

# 문서 링크는 그래프라 파일 하나만 보고는 판별할 수 없다. --all 에서만 돈다.
#   ① 죽은 링크 — 가리키는 파일이 없다
#   ② 고아 문서 — 아무도 가리키지 않아 저장소 어디서도 도달할 수 없다
# ②가 필요한 이유: 링크를 ADR 로 옮기는 정리 중 docs/design/erd.md 가 들어오는 링크 0건이 됐는데,
# 죽은 링크는 0건이라 커밋마다 검사를 통과했다. "링크가 살아 있나"와 "도달할 수 있나"는 다른 질문이다.
check_docs() {
  local list seen doc dir raw target abs base repo_p
  repo_p=$(pwd -P)
  list=$(mktemp) || return 0
  seen=$(mktemp) || { rm -f "$list"; return 0; }

  while IFS= read -r doc; do
    dir=$(dirname "$doc")
    awk '/^```/ { f = !f; next } !f' "$doc" 2>/dev/null | sed 's/`[^`]*`//g' \
      | grep -oE '\]\([^)]+\)' | sed 's/^](//; s/)$//' \
      | while IFS= read -r raw; do
          target=${raw%%#*}
          target=${target%% *}
          case "$target" in ''|http://*|https://*|mailto:*) continue ;; esac
          printf '%s\t%s\t%s\n' "$doc" "$dir" "$target"
        done
  done < <(git ls-files '*.md') > "$list"

  while IFS="$TAB" read -r doc dir target; do
    if [ ! -e "$dir/$target" ]; then
      report "$doc — 죽은 링크" "$target 을 가리키는데 그런 파일이 없다"
      continue
    fi
    abs=$(cd "$dir/$(dirname "$target")" 2>/dev/null && printf '%s/%s' "$(pwd -P)" "$(basename "$target")")
    [ -n "$abs" ] && printf '%s\n' "$abs" >> "$seen"
  done < "$list"

  local frag
  while IFS= read -r doc; do
    dir=$(dirname "$doc")
    grep -oE 'href="[^"]+"' "$doc" 2>/dev/null | sed 's/^href="//; s/"$//' \
      | while IFS= read -r raw; do
          case "$raw" in http://*|https://*|mailto:*|data:*|'#'*) continue ;; esac
          target=${raw%%#*}; frag=${raw#*#}; [ "$frag" = "$raw" ] && frag=''
          if [ ! -e "$dir/$target" ]; then
            printf 'DEAD\t%s\t%s\n' "$doc" "$target"; continue
          fi
          if [ -n "$frag" ] && ! grep -q "id=\"$frag\"" "$dir/$target" 2>/dev/null; then
            printf 'ANCHOR\t%s\t%s#%s\n' "$doc" "$target" "$frag"; continue
          fi
          abs=$(cd "$dir/$(dirname "$target")" 2>/dev/null && printf '%s/%s' "$(pwd -P)" "$(basename "$target")")
          [ -n "$abs" ] && printf 'OK\t%s\n' "$abs"
        done
  done < <(git ls-files 'docs/*.html' 'docs/**/*.html') > "$list"
  while IFS="$TAB" read -r kind a b; do
    case "$kind" in
      DEAD)   report "$a — 죽은 링크" "$b 을 가리키는데 그런 파일이 없다" ;;
      ANCHOR) report "$a — 죽은 앵커" "$b 을 가리키는데 대상에 그 id 가 없다. 절 번호가 바뀌었으면 인용을 같이 고친다" ;;
      OK)     printf '%s\n' "$a" >> "$seen" ;;
    esac
  done < "$list"

  while IFS= read -r doc; do
    base=$(basename "$doc")
    [ "$base" = "README.md" ] && continue
    [ "$doc" = "docs/index.html" ] && continue
    grep -qxF "$repo_p/$doc" "$seen" || \
      report "$doc — 고아 문서" "들어오는 링크가 0건이다. 어디서도 도달할 수 없으니 인용을 걸거나 문서를 지운다"
  done < <(git ls-files 'docs/' | grep -E '\.(md|html)$')

  rm -f "$list" "$seen"
}

# 판단이 필요한 것. 차단하지 않고 --all 에서만 목록을 낸다.
advisories() {
  local out

  # Service 의 @Transactional 누락은 인터페이스·추상 클래스·DB 를 쓰지 않는 구현체와 grep 으로 구별할 수 없다
  out=$(find src/main/java -path '*/service/*.java' 2>/dev/null | while read -r f; do
    grep -q 'interface \|abstract class ' "$f" && continue
    grep -q '@Transactional' "$f" || echo "  $f"
  done)
  [ -n "$out" ] && printf '\n[참고] Service 에 @Transactional 이 없다 — 정당할 수 있다(DB 미사용 구현체 등). 판단은 사람이 한다.\n%s\n' "$out"

  # CLAUDE.md 「재고 차감」: 인터페이스·추상 클래스·Redis 구현체에 @Transactional 을 붙이지 않는다.
  # 2026-09-08 기준 createForHotDeal 에 붙어 있어 규칙과 코드가 어긋난다. 어느 쪽을 고칠지 사람이 정한다
  out=$(for f in $(grep -rlE '^public (interface|abstract class)|Redis[A-Za-z]*Service' src/main/java/com/sparta/msa/commerce/domain/stock/service 2>/dev/null); do
    grep -n '@Transactional' "$f" 2>/dev/null | sed "s|^|  $f:|"
  done)
  [ -n "$out" ] && printf '\n[참고] 재고 전략의 인터페이스·추상 클래스·Redis 구현체에 @Transactional 이 있다 — CLAUDE.md 「재고 차감」과 어긋난다.\n%s\n' "$out"

  # 스타일 낱말 (전역 「일반어 검사」·재작성 계획 금지어). 문맥에 따라 맞을 수 있어 참고만
  out=$(git ls-files 'docs/*.md' 'docs/*.html' 'docs/**/*.html' README.md | while read -r f; do
    awk '/^```/ { c = !c; next } !c' "$f" | grep -cE '아니라|잡는|놓는|살 수|판다|갚' | sed "s|^|  $f: |" | grep -v ': 0$'
  done)
  [ -n "$out" ] && printf '\n[참고] 스타일 낱말(아니라·잡는·놓는·살 수·판다·갚) 출현 수 — 「A가 아니라 B」 반복과 구어 동사를 본다.\n%s\n' "$out"

  # 엔티티 @Comment 와 Flyway COMMENT 문구 일치 (하드룰 6)
  out=$(python3 - <<'PY' 2>/dev/null
import re,subprocess
files=subprocess.run(['git','ls-files','src/main/java','src/main/resources/db/migration'],capture_output=True,text=True).stdout.split()
jc=set(); sc=set()
for f in files:
    s=open(f,encoding='utf-8',errors='replace').read()
    if f.endswith('.java'): jc|=set(re.findall(r'@Comment\("([^"]+)"\)',s))
    elif f.endswith('.sql'): sc|=set(re.findall(r"COMMENT\s+'([^']+)'",s,re.I))
miss=sorted(jc-sc)
for m in miss: print('  @Comment 에만 있음: '+m)
PY
)
  [ -n "$out" ] && printf '\n[참고] @Comment 문구가 마이그레이션 COMMENT 에 없다 — 문구를 맞추거나 새 마이그레이션에 반영한다.\n%s\n' "$out"

  # 다른 문서의 수치 복제 (README 만 허용). 세 자리 이상 숫자·소수·퍼센트가 두 문서 이상에 있으면 목록
  out=$(python3 - <<'PY' 2>/dev/null
import re,subprocess,collections
files=[f for f in subprocess.run(['git','ls-files','docs/'],capture_output=True,text=True).stdout.split() if f.endswith(('.md','.html')) and f!='docs/index.html']
where=collections.defaultdict(set)
for f in files:
    s=open(f,encoding='utf-8',errors='replace').read()
    s=re.sub(r'<pre.*?</pre>|```.*?```','',s,flags=re.S)
    for n in set(re.findall(r'(?<![\w.])(\d{1,3}(?:,\d{3})+|\d+\.\d+|\d{3,}|\d+%)(?![\w.])',s)):
        where[n].add(f)
dup=[(n,sorted(fs)) for n,fs in where.items() if len(fs)>1]
for n,fs in sorted(dup,key=lambda x:-len(x[1]))[:15]: print('  %s: %s'%(n,', '.join(fs)))
PY
)
  [ -n "$out" ] && printf '\n[참고] 같은 수치가 문서 두 편 이상에 있다 — 복제인지 우연인지 본다. 원본이 바뀌면 사본은 아무도 안 고친다.\n%s\n' "$out"

  # 규칙 파일끼리의 어긋남: .claude/ 텍스트가 가리키는 저장소 경로가 실재하는가
  out=$(git ls-files '.claude/' CLAUDE.md | while read -r f; do
    grep -oE '(docs|src|k6)/[A-Za-z0-9_./{}-]+' "$f" | grep -v '{' | sed 's/[.:,)]*$//' | sort -u | while read -r p; do
      [ -e "$p" ] || printf '  %s → %s\n' "$f" "$p"
    done
  done)
  [ -n "$out" ] && printf '\n[참고] 규칙 파일이 가리키는 경로가 없다 — 규칙이 코드보다 뒤처졌다.\n%s\n' "$out"

  # AI 협업 문서 (전역 CLAUDE.md 「AI 협업 문서」). 없으면 참고, README 에 안내 한 줄이 있는지도 본다
  if ! git ls-files 'docs/**' | grep -qiE 'ai[-_]?collaboration|협업'; then
    printf '\n[참고] AI 협업 문서가 없다 — docs/ 에 한 편 두고 README 에서 한 줄로 안내한다.\n'
  elif ! grep -qiE 'AI|Claude' README.md; then
    printf '\n[참고] README 에 AI 협업 문서 안내가 없다.\n'
  fi

  # README 첫 화면 (두 번째 ## 앞까지) 2,000자 — 32강 3분 기준. 13a 뒤에 위반으로 올린다
  out=$(awk 'BEGIN{n=0} /^## /{n++; if(n==2) exit} {print}' README.md | wc -m | tr -d ' ')
  [ "${out:-0}" -gt 2000 ] && printf '\n[참고] README 첫 화면이 %s자다 — 3분 기준 2,000자를 넘는다.\n' "$out"
  return 0
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
      */db/migration/V*.sql)
        git ls-files --error-unmatch "$target" >/dev/null 2>&1 && \
          report "$target — 적용된 마이그레이션 수정" "Flyway 체크섬이 어긋나 통합 테스트가 전부 실패한다. 새 V{n} 파일을 추가한다"
        ;;
    esac
    ;;
  --file)
    [ $# -ge 2 ] || die "--file 에 경로가 없다"
    target=$(rel "$2")
    dispatch "$target"
    ;;
  --all)
    while IFS= read -r f; do dispatch "$f"; done < <(git ls-files '*.java')
    check_docs
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
