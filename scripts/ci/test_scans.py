"""Scanner checks. Fixtures are fake and must not appear in scanner output."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.ci.name_denylist import load_terms, scan as scan_names
from scripts.ci.secrets_scan import scan as scan_secrets

FAKE_NAME = "Exampleforbidden"
FAKE_TOKEN = "ghp_" + ("A" * 36)


def _git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True, text=True)


def _repo_with(text: str, message: str) -> tuple[tempfile.TemporaryDirectory[str], str, str]:
    tmp = tempfile.TemporaryDirectory(prefix="nuvio-ci-scan-")
    root = Path(tmp.name)
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "ci@example.com")
    _git(root, "config", "user.name", "CI Fixture")
    (root / "keep.txt").write_text("unchanged\n", encoding="utf-8")
    _git(root, "add", "keep.txt")
    _git(root, "commit", "-qm", "base")
    base = subprocess.run(
        ["git", "-C", root, "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    (root / "added.txt").write_text(text, encoding="utf-8")
    _git(root, "add", "added.txt")
    _git(root, "commit", "-qm", message)
    head = subprocess.run(
        ["git", "-C", root, "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    return tmp, base, head


class NameDenylistTests(unittest.TestCase):
    def test_missing_list_is_none(self) -> None:
        with tempfile.TemporaryDirectory() as home:
            env = os.environ.copy()
            env.pop("NAME_DENYLIST", None)
            env["HOME"] = home
            previous = os.environ
            os.environ.clear()
            os.environ.update(env)
            try:
                self.assertIsNone(load_terms())
            finally:
                os.environ.clear()
                os.environ.update(previous)

    def test_hit_is_reported_without_the_term(self) -> None:
        tmp, base, head = _repo_with(f"hello {FAKE_NAME} there\n", "ordinary message")
        self.addCleanup(tmp.cleanup)
        hits = scan_names(tmp.name, base, head, [FAKE_NAME])
        self.assertEqual(hits, ["name denylist: hit in added.txt"])
        self.assertNotIn(FAKE_NAME, "\n".join(hits))

    def test_clean_diff(self) -> None:
        tmp, base, head = _repo_with("hello\n", "ordinary message")
        self.addCleanup(tmp.cleanup)
        self.assertEqual(scan_names(tmp.name, base, head, [FAKE_NAME]), [])


class SecretsScanTests(unittest.TestCase):
    def test_token_is_reported_without_the_value(self) -> None:
        tmp, base, head = _repo_with(f"token = {FAKE_TOKEN}\n", "ordinary message")
        self.addCleanup(tmp.cleanup)
        hits = scan_secrets(tmp.name, base, head)
        self.assertTrue(any(hit.startswith("secrets scan: github-token in added.txt") for hit in hits))
        self.assertNotIn(FAKE_TOKEN, "\n".join(hits))

    def test_placeholder_assignment_is_ignored(self) -> None:
        tmp, base, head = _repo_with('api_key = "your-api-key-here"\n', "ordinary message")
        self.addCleanup(tmp.cleanup)
        self.assertEqual(scan_secrets(tmp.name, base, head), [])


if __name__ == "__main__":
    unittest.main()
