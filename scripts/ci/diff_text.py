"""Text added between a base commit and a head commit."""

from __future__ import annotations

import subprocess
from dataclasses import dataclass


@dataclass(frozen=True)
class AddedLine:
    path: str
    line: int
    text: str


def git(repo: str, args: list[str]) -> str:
    result = subprocess.run(
        ["git", "-C", repo, *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def merge_base(repo: str, base: str, head: str) -> str:
    return git(repo, ["merge-base", base, head]).strip()


def added_lines(repo: str, base: str, head: str) -> list[AddedLine]:
    """Lines introduced on head since it diverged from base."""
    base_commit = merge_base(repo, base, head)
    diff = git(
        repo,
        [
            "diff",
            "--no-color",
            "--no-ext-diff",
            "-U0",
            "--find-renames",
            base_commit,
            head,
        ],
    )
    return _parse_added(diff)


def commit_texts(repo: str, base: str, head: str) -> list[tuple[str, str]]:
    """Commit subjects and bodies reachable from head but not from base."""
    log = git(
        repo,
        ["log", "--format=%H%x1f%B%x1e", f"{base}..{head}"],
    )
    commits: list[tuple[str, str]] = []
    for record in log.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        sha, _, message = record.partition("\x1f")
        commits.append((sha.strip(), message))
    return commits


def _parse_added(diff: str) -> list[AddedLine]:
    lines: list[AddedLine] = []
    path = ""
    new_line = 0
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            name = raw[4:]
            if name.startswith("b/"):
                name = name[2:]
            path = "" if name == "/dev/null" else name
            continue
        if raw.startswith("@@"):
            # @@ -l,s +l,s @@ or @@ -l +l @@
            plus = raw.split(" ")
            new_span = next((part for part in plus if part.startswith("+")), "+0")
            start = new_span[1:].split(",")[0]
            new_line = int(start)
            continue
        if raw.startswith("Binary files "):
            path = ""
            continue
        if not path or not raw.startswith("+") or raw.startswith("+++"):
            continue
        lines.append(AddedLine(path, new_line, raw[1:]))
        new_line += 1
    return lines
