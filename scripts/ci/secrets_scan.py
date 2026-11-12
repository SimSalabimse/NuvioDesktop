"""Fail when a diff adds something that looks like a secret or an addon URL.

Matches are reported by kind and path. The matched text is not printed.
"""

from __future__ import annotations

import argparse
import re
import sys

from scripts.ci.diff_text import added_lines, commit_texts


PLACEHOLDER = re.compile(
    r"(?i)(?:changeme|example|placeholder|your[-_]|redacted|\bx{3,}\b|todo|"
    r"ci-test-secret|<.*>|\$\{)"
)

RULES: list[tuple[str, re.Pattern[str]]] = [
    ("private-key", re.compile(r"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----")),
    (
        "github-token",
        re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b|github_pat_[A-Za-z0-9_]{20,}"),
    ),
    ("aws-access-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z\-_]{20,}\b")),
    (
        "jwt",
        re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
    ),
    (
        "assigned-secret",
        re.compile(
            r"(?i)\b(?:api[_-]?key|client[_-]?secret|secret|password|passwd|"
            r"access[_-]?token|private[_-]?key)\b\s*[:=]\s*['\"][^'\"]{8,}['\"]"
        ),
    ),
    ("url-credential", re.compile(r"https?://[^\s'\":]+:[^\s'\"@/]+@")),
    (
        "query-secret",
        re.compile(r"(?i)[?&](?:api[_-]?key|token|access[_-]?token|secret|key)=[^&\s'\"]{8,}"),
    ),
    (
        "torbox-key",
        re.compile(
            r"(?i)\btorbox\b.{0,60}(?:api[_-]?key|token|secret)\s*[:=]\s*['\"][^'\"]{8,}['\"]"
        ),
    ),
    ("addon-url", re.compile(r"(?i)\bstremio://\S+")),
    (
        "addon-url",
        re.compile(r"(?i)https?://[^\s'\"<>]+/(?:addon/|manifest\.json)\b[^\s'\"<>]*"),
    ),
]


def _hits_in(text: str) -> list[str]:
    kinds: list[str] = []
    for kind, pattern in RULES:
        match = pattern.search(text)
        if match is None:
            continue
        if kind in {"assigned-secret", "query-secret", "torbox-key", "addon-url"} and PLACEHOLDER.search(
            match.group(0)
        ):
            continue
        kinds.append(kind)
    return kinds


def scan(repo: str, base: str, head: str) -> list[str]:
    found: list[str] = []
    seen: set[tuple[str, str]] = set()

    def add(kind: str, where: str) -> None:
        key = (kind, where)
        if key in seen:
            return
        seen.add(key)
        found.append(f"secrets scan: {kind} in {where}")

    for line in added_lines(repo, base, head):
        for kind in _hits_in(line.text):
            add(kind, line.path)
    for sha, message in commit_texts(repo, base, head):
        for kind in _hits_in(message):
            add(kind, f"commit {sha[:12]}")
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Scan a diff for secrets.")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args(argv)
    try:
        hits = scan(args.repo, args.base, args.head)
    except Exception as error:  # noqa: BLE001 — one line, no matched text
        print(f"secrets scan: scan failed ({error.__class__.__name__})")
        return 1
    if hits:
        for hit in hits:
            print(hit)
        return 1
    print("secrets scan: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
