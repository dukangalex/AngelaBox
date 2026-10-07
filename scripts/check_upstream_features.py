#!/usr/bin/env python3
"""Fail if official sing-box inbound/outbound type constants are missing from chain-dev.

Usage:
  python3 scripts/check_upstream_features.py --official /path/to/SagerNet/sing-box --fork /path/to/dukangalex/sing-box
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TYPE_RE = re.compile(r'^\s*Type[A-Za-z0-9]+\s*=\s*"([^"]+)"', re.MULTILINE)


def types_in(tree: Path) -> dict[str, str]:
    found: dict[str, str] = {}
    const_dir = tree / "constant"
    if not const_dir.is_dir():
        raise SystemExit(f"missing constant/ under {tree}")
    for path in sorted(const_dir.rglob("*.go")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in TYPE_RE.finditer(text):
            found[match.group(1)] = str(path.relative_to(tree))
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--official", required=True, type=Path)
    parser.add_argument("--fork", required=True, type=Path)
    args = parser.parse_args()
    official = types_in(args.official)
    fork = types_in(args.fork)
    missing = sorted(set(official) - set(fork))
    extra = sorted(set(fork) - set(official))
    print(f"official types: {len(official)}")
    print(f"fork types:     {len(fork)}")
    if extra:
        print("fork extras (allowed): " + ", ".join(extra))
    if missing:
        print("FAIL: official types missing from chain-dev:")
        for name in missing:
            print(f"  - {name}  (official: {official[name]})")
        return 1
    print("official type constants are present in chain-dev")
    return 0


if __name__ == "__main__":
    sys.exit(main())
