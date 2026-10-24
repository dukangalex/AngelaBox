#!/usr/bin/env python3
"""Guards against guards that hard-code the thing they are supposed to check.

Twice in one day a release died because a script asserted a literal that was
only true on the day it was written:

  * test_security_guards pinned KERNEL_COMMIT=9c0a0c7… and
    KERNEL_TAG=v1.15.0-chain.7, so the build that shipped chain.8 failed its
    own release gate (run 40091084437, 20 s in, before compiling anything).
  * test_override_guards pinned KERNEL_UPSTREAM=1.15.0-alpha.10 and required
    the README to repeat it, so the alpha.13 bump failed the same way.

Both are the same mistake: treating the current value as an invariant. The
pin lives in version.properties and nowhere else. Anything that needs to know
which kernel ships this build must read it from there — the release workflows
already do (`VERSION_NAME`/`KERNEL_COMMIT` are read, not repeated).

What is allowed to be a literal here:
  * the APK signing certificate SHA-256 — a security invariant that must NOT
    move between releases (scripts/test_security_guards.py, verify_apk_signature.py);
  * action pins (`actions/checkout@<sha>`) — supply-chain hygiene;
  * negative assertions ("this forbidden commit must not appear");
  * comments and docs, which are allowed to talk about history.

Anything with `# pin-guard: allow` on the same line is skipped.
"""

from __future__ import annotations

import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

# A pin-shaped upstream version, a fork tag, or a kernel commit.
UPSTREAM_RE = re.compile(r"\b\d+\.\d+\.\d+-alpha\.\d+\b")
CHAIN_TAG_RE = re.compile(r"\bv\d+\.\d+\.\d+-chain\.\d+\b")
COMMIT_RE = re.compile(r"\b[0-9a-f]{40}\b")
ALLOW_MARK = "pin-guard: allow"


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def props() -> dict[str, str]:
    pins: dict[str, str] = {}
    for line in read("version.properties").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, _, value = line.partition("=")
            pins[key.strip()] = value.strip()
    return pins


def code_lines(text: str) -> list[tuple[int, str]]:
    """Lines with trailing comments and full-line comments dropped."""
    kept: list[tuple[int, str]] = []
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        kept.append((number, line.split("#", 1)[0]))
    return kept


def offenders(path: str, pins: dict[str, str]) -> list[str]:
    """One message per offending literal, preferring the most specific wording."""
    found: dict[tuple[int, str], str] = {}
    current = {pins.get(key, "") for key in ("KERNEL_UPSTREAM", "KERNEL_TAG", "KERNEL_COMMIT")}
    current.discard("")

    def note(number: int, match: str, label: str) -> None:
        found.setdefault((number, match), f"{path}:{number}: hard-codes {label} '{match}'")

    for number, line in code_lines(read(path)):
        if ALLOW_MARK in line:
            continue
        for pin in current:
            if pin in line:
                note(number, pin, "the current pin")
        for pattern, label in ((UPSTREAM_RE, "an upstream version"), (CHAIN_TAG_RE, "a kernel tag")):
            for match in pattern.findall(line):
                note(number, match, label)
        # Action pins and the signing certificate are not kernel pins.
        if "uses:" in line or "action-gh-release" in line:
            continue
        for match in COMMIT_RE.findall(line):
            if match == pins.get("KERNEL_COMMIT"):
                note(number, match, "the current kernel commit")
    return [found[key] for key in sorted(found)]


def guard_files() -> list[str]:
    return sorted(p.name for p in (ROOT / "scripts").glob("test_*.py") if p.name != "test_pin_guards.py")


def workflow_files() -> list[str]:
    return sorted(f".github/workflows/{p.name}" for p in (ROOT / ".github/workflows").glob("*.yml"))


def test_no_guard_hard_codes_the_pin() -> None:
    pins = props()
    problems: list[str] = []
    for name in guard_files():
        problems.extend(offenders(f"scripts/{name}", pins))
    if problems:
        raise AssertionError(
            "guards must read the pin from version.properties, not repeat it:\n  "
            + "\n  ".join(problems)
        )


def test_no_workflow_hard_codes_the_pin() -> None:
    pins = props()
    problems: list[str] = []
    for name in workflow_files():
        # Workflows may reference the signing certificate; they must not
        # repeat a kernel version or tag, because both tracks pin differently.
        for number, line in code_lines(read(name)):
            if ALLOW_MARK in line:
                continue
            for pattern, label in ((UPSTREAM_RE, "upstream version"), (CHAIN_TAG_RE, "kernel tag")):
                for match in pattern.findall(line):
                    problems.append(f"{name}:{number}: hard-codes a {label} '{match}'")
    if problems:
        raise AssertionError("\n  ".join(problems))


def test_pin_is_consistent_with_the_stable_track() -> None:
    beta = props()
    stable: dict[str, str] = {}
    for line in read("version-stable.properties").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, _, value = line.partition("=")
            stable[key.strip()] = value.strip()
    for key, pattern in (("KERNEL_UPSTREAM", UPSTREAM_RE), ("KERNEL_TAG", CHAIN_TAG_RE)):
        for track, values in (("version.properties", beta), ("version-stable.properties", stable)):
            value = values.get(key, "")
            if not pattern.fullmatch(value):
                raise AssertionError(f"{track}: {key}='{value}' is not a valid pin")
    if not COMMIT_RE.fullmatch(beta.get("KERNEL_COMMIT", "")):
        raise AssertionError("version.properties: KERNEL_COMMIT is not a 40-hex commit")
    if not COMMIT_RE.fullmatch(stable.get("KERNEL_COMMIT", "")):
        raise AssertionError("version-stable.properties: KERNEL_COMMIT is not a 40-hex commit")
    beta_code = int(beta["VERSION_CODE"])
    stable_code = int(stable["VERSION_CODE"])
    # The beta track must never hand out a versionCode a stable release
    # already used: Android refuses to downgrade, so a collision strands users
    # on whichever build installed first.
    if beta_code <= stable_code:
        raise AssertionError(
            f"beta versionCode {beta_code} must stay above the stable track's {stable_code}"
        )


def test_detector_actually_detects() -> None:
    """A guard that cannot fail is worse than no guard."""
    pins = props()
    sample = (
        f'assert "KERNEL_TAG={pins["KERNEL_TAG"]}" in props\n'
        f'assert "KERNEL_UPSTREAM={pins["KERNEL_UPSTREAM"]}" in props\n'
        f'assert f"KERNEL_COMMIT={pins["KERNEL_COMMIT"]}" in props\n'
        'assert "v0.0.0-chain.1" not in text\n'
        "\n"
        f"# historical note about {pins['KERNEL_TAG']} is fine\n"
        f"ok = 1  # {ALLOW_MARK}\n"
    )
    with tempfile.TemporaryDirectory() as directory:
        path = pathlib.Path(directory) / "sample.py"
        path.write_text(sample, encoding="utf-8")
        original = ROOT
        try:
            globals()["ROOT"] = pathlib.Path(directory)
            hits = offenders("sample.py", pins)
        finally:
            globals()["ROOT"] = original
    if len(hits) != 4:
        raise AssertionError(f"expected 4 detections (3 current pins + 1 stale tag), got {len(hits)}: {hits}")
    # The comment and the allow-marked line must not raise anything.
    if any("historical" in hit for hit in hits):
        raise AssertionError("comments must not be scanned")


def main() -> int:
    pins = props()
    for key in ("KERNEL_UPSTREAM", "KERNEL_TAG", "KERNEL_COMMIT"):
        if not pins.get(key):
            print(f"FAIL version.properties is missing {key}", file=sys.stderr)
            return 1

    tests = [
        test_no_guard_hard_codes_the_pin,
        test_no_workflow_hard_codes_the_pin,
        test_pin_is_consistent_with_the_stable_track,
        test_detector_actually_detects,
    ]
    failed = 0
    for test in tests:
        try:
            test()
            print(f"ok  {test.__name__}")
        except Exception as e:  # noqa: BLE001 - guard harness reports everything
            failed += 1
            print(f"FAIL {test.__name__}: {e}", file=sys.stderr)
    if failed:
        print(f"{failed} failed", file=sys.stderr)
        return 1
    print("all pin guards passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
