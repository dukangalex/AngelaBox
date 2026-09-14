#!/usr/bin/env python3
"""Build Telegram announcement text + inline keyboard JSON.

Layout matches a typical channel release post: version, source URL, changelog,
then a download button. The APK itself is uploaded separately as a document.
"""
from __future__ import annotations

import json
import os
from pathlib import Path


def main() -> None:
    tag = os.environ["TAG"]
    sha = os.environ.get("SHA256", "").strip()
    url = os.environ["RELEASE_URL"]
    apk_url = os.environ.get(
        "APK_URL",
        f"https://github.com/dukangalex/AngelaBox/releases/download/{tag}/AngelaBox-android.apk",
    )
    run_url = os.environ.get("RUN_URL", "").strip()
    notes_file = Path(os.environ.get("NOTES_FILE", "docs/RELEASE_NOTES.md"))
    notes = notes_file.read_text(encoding="utf-8").strip() if notes_file.is_file() else ""
    if len(notes) > 2800:
        notes = notes[:2800].rstrip() + "\n…"
    lines = [
        f"AngelaBox {tag}",
        "",
        url,
    ]
    if run_url:
        lines.extend(["", run_url])
    lines.extend(
        [
            "",
            notes or f"AngelaBox {tag} 已发布。",
            "",
            "请安装 AngelaBox-android.apk。",
        ]
    )
    if sha:
        lines.extend(
            [
                "",
                "安装包校验 SHA-256（用于核对文件是否完整、是否被篡改）：",
                sha,
            ]
        )
    Path("telegram-message.txt").write_text("\n".join(lines).strip() + "\n", encoding="utf-8")
    Path("telegram-markup.json").write_text(
        json.dumps(
            {
                "inline_keyboard": [
                    [
                        {"text": "下载 AngelaBox-android.apk", "url": apk_url},
                        {"text": "GitHub Release", "url": url},
                    ]
                ]
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    caption = [
        f"AngelaBox {tag}",
        "",
        "点这条消息即可安装。",
    ]
    if sha:
        caption.extend(
            [
                "",
                "安装包 SHA-256（核对文件是否完整）：",
                sha,
            ]
        )
    caption.extend(["", url])
    text = "\n".join(caption)
    if len(text) > 1000:
        text = text[:1000].rstrip() + "…"
    Path("telegram-caption.txt").write_text(text + "\n", encoding="utf-8")
    print("wrote telegram-message.txt, telegram-markup.json, telegram-caption.txt")


if __name__ == "__main__":
    main()
