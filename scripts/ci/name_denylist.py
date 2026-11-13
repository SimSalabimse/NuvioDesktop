"""Fail when a diff introduces a name from the local denylist.

The list is read from NAME_DENYLIST or ~/.config/nuvio/name-denylist.
A missing or empty list fails the run. The list and the matched text are
never written to stdout or stderr.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

from scripts.ci.diff_text import added_lines, commit_texts


def list_path() -> Path:
    return Path.home() / ".config" / "nuvio" / "name-denylist"


def load_terms() -> list[str] | None:
    raw = os.environ.get("NAME_DENYLIST")
    if raw is None or not raw.strip():
        path = list_path()
        if not path.is_file():
            return None
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError:
            return None
    terms = []
    for line in raw.replace(",", "\n").splitlines():
        term = line.strip()
        if not term or term.startswith("#"):
            continue
        terms.append(term)
    return terms or None


def term_pattern(terms: list[str]) -> re.Pattern[str]:
    parts: list[str] = []
    for term in terms:
        escaped = re.escape(term)
        if re.match(r"\w", term) and re.search(r"\w$", term):
            parts.append(rf"\b{escaped}\b")
        else:
            parts.append(escaped)
    return re.compile("|".join(parts), re.IGNORECASE)


def scan(repo: str, base: str, head: str, terms: list[str]) -> list[str]:
    pattern = term_pattern(terms)
    hits: list[str] = []
    seen: set[str] = set()

    def add(where: str) -> None:
        if where not in seen:
            seen.add(where)
            hits.append(where)

    for line in added_lines(repo, base, head):
        if pattern.search(line.path) or pattern.search(line.text):
            add(f"name denylist: hit in {line.path}")
    for sha, message in commit_texts(repo, base, head):
        if pattern.search(message):
            add(f"name denylist: hit in commit {sha[:12]}")
    return hits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Scan a diff for denied names.")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args(argv)

    terms = load_terms()
    if terms is None:
        print("name denylist: list missing")
        return 1
    try:
        hits = scan(args.repo, args.base, args.head, terms)
    except Exception as error:  # noqa: BLE001 — git failures stay one line, no list
        print(f"name denylist: scan failed ({error.__class__.__name__})")
        return 1
    if hits:
        for hit in hits:
            print(hit)
        return 1
    print("name denylist: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
