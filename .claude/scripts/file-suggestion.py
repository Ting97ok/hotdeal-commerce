#!/usr/bin/env python3
"""
Claude Code fileSuggestion 스크립트.
현재 작업 중인 파일 경로를 기반으로 관련 파일을 추천합니다.

commerce(모놀리식) 구조 전제:
  src/main/java/com/sparta/msa/commerce/domain/{도메인}/{계층}/...
  설계 문서: 루트 docs/{도메인}/
"""
import json
import subprocess
import sys
from pathlib import Path

# 도메인 디렉토리 바로 아래 위치하는 계층 디렉토리들.
# commerce 는 domain/{name}/{layer} 구조이므로 그룹 디렉토리인 "domain" 은 키워드에서 제외한다
# (포함하면 domain 의 부모인 "commerce" 를 도메인으로 잘못 추출).
LAYER_DIRS = (
    "controller", "service", "facade", "repository", "entity",
    "dto", "mapper", "exception", "constant", "event",
    "listener", "scheduled", "util",
)


def get_recent_files():
    """git에서 최근 수정된 파일 목록을 가져옵니다."""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "HEAD~5"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            return [f for f in result.stdout.strip().split("\n") if f]
    except Exception:
        pass
    return []


def get_staged_files():
    """스테이징된 파일 목록을 가져옵니다."""
    try:
        result = subprocess.run(
            ["git", "diff", "--cached", "--name-only"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            return [f for f in result.stdout.strip().split("\n") if f]
    except Exception:
        pass
    return []


def get_unstaged_files():
    """수정되었지만 스테이징되지 않은 파일 목록을 가져옵니다."""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            return [f for f in result.stdout.strip().split("\n") if f]
    except Exception:
        pass
    return []


def get_untracked_files():
    """추적되지 않는 새 파일 목록을 가져옵니다."""
    try:
        result = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            return [f for f in result.stdout.strip().split("\n") if f]
    except Exception:
        pass
    return []


def extract_domain(filepath):
    """파일 경로에서 도메인명을 추출합니다.

    계층 디렉토리(controller/service/entity/dto/mapper/...) 바로 앞 디렉토리가 도메인.
    예: domain/product/entity/Product.java        -> product
        domain/product/dto/request/CreateX.java   -> product (dto 가 먼저 매칭)
    """
    parts = Path(filepath).parts
    for i, part in enumerate(parts):
        if part in LAYER_DIRS and i > 0:
            return parts[i - 1]
    return None


def find_domain_files(domain):
    """특정 도메인의 관련 파일을 찾습니다 (모놀리식 단일 src)."""
    try:
        base = "src/main/java"
        result = subprocess.run(
            ["find", base, "-type", "f", "-name", "*.java", "-path", f"*/{domain}/*"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            return [f for f in result.stdout.strip().split("\n") if f]
    except Exception:
        pass
    return []


def main():
    seen = set()
    suggestions = []

    def add(filepath):
        if filepath and filepath not in seen and Path(filepath).exists():
            seen.add(filepath)
            suggestions.append(filepath)

    # 1. 현재 변경된 파일들 (가장 높은 우선순위)
    for f in get_unstaged_files():
        add(f)
    for f in get_staged_files():
        add(f)
    for f in get_untracked_files():
        add(f)

    # 2. 최근 커밋에서 수정된 파일들
    for f in get_recent_files():
        add(f)

    # 3. 변경된 파일의 도메인에서 관련 파일 추천
    domains = set()
    for f in list(suggestions):
        domain = extract_domain(f)
        if domain and domain not in domains:
            domains.add(domain)
            for related in find_domain_files(domain):
                add(related)

    # 4. 설계 문서 추천 (도메인 단위 — 루트 docs/{도메인}/)
    for domain in domains:
        docs_path = f"docs/{domain}"
        if Path(docs_path).exists():
            for doc in Path(docs_path).glob("*.md"):
                add(str(doc))

    # 최대 30개로 제한
    print(json.dumps(suggestions[:30]))


if __name__ == "__main__":
    main()
