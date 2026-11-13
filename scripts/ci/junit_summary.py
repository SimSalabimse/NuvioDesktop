"""Print a one-line total from Gradle JUnit XML."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def summarize(directory: Path) -> tuple[int, int, int, int, int]:
    files = tests = failures = errors = skipped = 0
    if not directory.is_dir():
        return files, tests, failures, errors, skipped
    for path in sorted(directory.glob("TEST-*.xml")):
        files += 1
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            tests += int(suite.attrib.get("tests", "0"))
            failures += int(suite.attrib.get("failures", "0"))
            errors += int(suite.attrib.get("errors", "0"))
            skipped += int(suite.attrib.get("skipped", "0"))
    return files, tests, failures, errors, skipped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Summarize JUnit XML.")
    parser.add_argument("directory")
    parser.add_argument("--label", default="junit")
    args = parser.parse_args(argv)
    files, tests, failures, errors, skipped = summarize(Path(args.directory))
    print(
        f"{args.label}: files={files} tests={tests} failures={failures} "
        f"errors={errors} skipped={skipped}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
