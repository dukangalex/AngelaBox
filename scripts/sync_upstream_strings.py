#!/usr/bin/env python3
"""Copy official SagerNet zh strings onto shared keys.

AngelaBox-only keys (overlay_*, chain_*, and anything not in upstream) stay.
app_name stays AngelaBox. Run before each release so 跟随系统 matches official.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KEEP_KEYS = {"app_name"}
KEEP_PREFIXES = (
    "overlay_",
    "chain_",
    "qs_",
    "update_current_profile",
    "theme_pure_black",
    "theme_color",
)

STRING_RE = re.compile(
    r'<string\s+name="([^"]+)"([^>]*)>(.*?)</string>',
    re.DOTALL,
)


def parse_strings(text: str) -> dict[str, tuple[str, str]]:
    found: dict[str, tuple[str, str]] = {}
    for match in STRING_RE.finditer(text):
        found[match.group(1)] = (match.group(2), match.group(3))
    return found


def should_keep(name: str) -> bool:
    if name in KEEP_KEYS:
        return True
    return any(name.startswith(prefix) or name == prefix for prefix in KEEP_PREFIXES)


def merge(ours_path: Path, official_path: Path) -> tuple[str, int]:
    ours = ours_path.read_text(encoding="utf-8")
    official = parse_strings(official_path.read_text(encoding="utf-8"))
    replaced = 0

    def repl(match: re.Match[str]) -> str:
        nonlocal replaced
        name, attrs, body = match.group(1), match.group(2), match.group(3)
        if name not in official or should_keep(name):
            return match.group(0)
        off_attrs, off_body = official[name]
        if body == off_body:
            return match.group(0)
        replaced += 1
        use_attrs = attrs if 'translatable=' in attrs else off_attrs
        return f'<string name="{name}"{use_attrs}>{off_body}</string>'

    merged = STRING_RE.sub(repl, ours)
    return merged, replaced


def main() -> int:
    mapping = {
        ROOT / "app/src/main/res/values-zh-rCN/strings.xml": ROOT / "scripts/upstream_strings/zh-rCN.xml",
        ROOT / "app/src/main/res/values-zh-rTW/strings.xml": ROOT / "scripts/upstream_strings/zh-rTW.xml",
    }
    total = 0
    for ours, official in mapping.items():
        if not ours.is_file() or not official.is_file():
            print(f"missing {ours} or {official}", file=sys.stderr)
            return 1
        merged, n = merge(ours, official)
        ours.write_text(merged, encoding="utf-8")
        print(f"{ours.relative_to(ROOT)}: restored {n} keys from official")
        total += n
    print(f"total restored: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
