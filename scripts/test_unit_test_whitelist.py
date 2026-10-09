#!/usr/bin/env python3
"""D6 guard: every JVM unit test class must be in EACH workflow's --tests whitelist.

ci.yml and release-chainbox.yml run an explicit `--tests` whitelist instead of
the full `testDebugUnitTest` task. A new *Test class that is not listed is
silently skipped by that workflow. This guard fails in that case (and on stale
whitelist entries that no longer map to a file). Each workflow file is checked
independently: being listed in only one of them is still a failure.

Wire into CI (web edit, GitHub App cannot write .github/workflows):
    python3 scripts/test_unit_test_whitelist.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_SRC = ROOT / "app" / "src" / "test"
WORKFLOWS = [
    ROOT / ".github" / "workflows" / "ci.yml",
    ROOT / ".github" / "workflows" / "release-chainbox.yml",
]

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
CLASS_RE = re.compile(r"^\s*(?:open\s+|abstract\s+)?class\s+(\w+)", re.MULTILINE)
TESTS_FLAG_RE = re.compile(r"--tests\s+([^\s\\]+)")


def find_test_classes() -> set[str]:
    classes: set[str] = set()
    for path in sorted(TEST_SRC.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        package = PACKAGE_RE.search(text)
        if not package:
            print(f"WARN: no package in {path}", file=sys.stderr)
            continue
        for match in CLASS_RE.finditer(text):
            name = match.group(1)
            if name.endswith("Test") or name.startswith("Test"):
                classes.add(f"{package.group(1)}.{name}")
    return classes


def find_whitelisted() -> dict[str, set[str]]:
    per_file: dict[str, set[str]] = {}
    for workflow in WORKFLOWS:
        text = workflow.read_text(encoding="utf-8")
        per_file[workflow.name] = set(TESTS_FLAG_RE.findall(text))
    return per_file


def main() -> int:
    test_classes = find_test_classes()
    per_file = find_whitelisted()
    if not test_classes:
        print("FAIL: no test classes found under app/src/test", file=sys.stderr)
        return 1
    ok = True
    # Every workflow runs its own --tests list; a class must be listed in EACH
    # file, otherwise that workflow silently skips it.
    for name in sorted(per_file):
        whitelisted = per_file[name]
        missing = sorted(test_classes - whitelisted)
        stale = sorted(c for c in whitelisted if c not in test_classes)
        if missing:
            ok = False
            print(f"FAIL: [{name}] test classes not in --tests whitelist:", file=sys.stderr)
            for cls in missing:
                print(f"  - {cls}", file=sys.stderr)
        if stale:
            ok = False
            print(f"FAIL: [{name}] stale --tests entries with no matching test file:", file=sys.stderr)
            for cls in stale:
                print(f"  - {cls}", file=sys.stderr)
    if ok:
        print(f"OK: {len(test_classes)} test classes whitelisted in every workflow file.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
