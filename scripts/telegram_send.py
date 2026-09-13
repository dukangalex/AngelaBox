#!/usr/bin/env python3
"""Send Telegram channel posts without curl -F.

GitHub Actions masks the substring AngelaBox (from TG_CHANNEL_ID=@AngelaBox)
inside logs and can corrupt curl's @filename form field. Use stdlib multipart
instead so the APK is always read from disk.

sendMessage runs first so a large sendDocument timeout cannot swallow the
changelog. sendDocument streams the APK in chunks and retries on timeout.
"""
from __future__ import annotations

import http.client
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BOUNDARY = "----AngelaBoxForm7a3c"
HOST = "api.telegram.org"
CHUNK = 256 * 1024
JSON_TIMEOUT = int(os.environ.get("TG_JSON_TIMEOUT", "60"))
SEND_TIMEOUT = int(os.environ.get("TG_TIMEOUT", "120"))
RETRIES = int(os.environ.get("TG_RETRIES", "3"))


def _log(msg: str) -> None:
    print(msg, flush=True)


def _log_err(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def _parse(raw: bytes, method: str) -> dict:
    parsed = json.loads(raw.decode("utf-8"))
    _log(json.dumps(parsed, ensure_ascii=False, indent=2)[:4000])
    if not parsed.get("ok"):
        raise SystemExit(f"Telegram {method} failed: {parsed}")
    return parsed


def post_json(token: str, method: str, fields: dict) -> dict:
    url = f"https://{HOST}/bot{token}/{method}"
    payload = json.dumps(fields).encode("utf-8")
    req = urllib.request.Request(url, data=payload, method="POST")
    req.add_header("Content-Type", "application/json")
    ctx = ssl.create_default_context()
    last_err: Exception | None = None
    for attempt in range(1, RETRIES + 1):
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=JSON_TIMEOUT) as resp:
                raw = resp.read()
            _log(f"{method} ok in {time.monotonic() - t0:.1f}s (attempt {attempt})")
            return _parse(raw, method)
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            _log_err(raw.decode("utf-8", "replace"))
            if exc.code in {429, 500, 502, 503, 504} and attempt < RETRIES:
                last_err = exc
                time.sleep(5 * attempt)
                continue
            raise SystemExit(f"Telegram {method} HTTP {exc.code}") from exc
        except (TimeoutError, urllib.error.URLError, OSError) as exc:
            last_err = exc
            _log_err(f"{method} attempt {attempt}/{RETRIES} failed after {time.monotonic() - t0:.1f}s: {exc}")
            if attempt < RETRIES:
                time.sleep(5 * attempt)
                continue
            raise SystemExit(f"Telegram {method} failed after {RETRIES} attempts: {exc}") from exc
    raise SystemExit(f"Telegram {method} failed: {last_err}")


def _multipart_preamble(fields: dict[str, str], filename: str, content_type: str) -> bytes:
    crlf = b"\r\n"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend(
            [
                f"--{BOUNDARY}".encode(),
                f'Content-Disposition: form-data; name="{name}"'.encode(),
                b"",
                value.encode("utf-8"),
            ]
        )
    chunks.extend(
        [
            f"--{BOUNDARY}".encode(),
            (
                f'Content-Disposition: form-data; name="document"; '
                f'filename="{filename}"'
            ).encode(),
            f"Content-Type: {content_type}".encode(),
            b"",
        ]
    )
    return crlf.join(chunks) + crlf


def post_document(token: str, fields: dict[str, str], apk: Path) -> dict:
    filename = "AngelaBox-android.apk"
    content_type = "application/vnd.android.package-archive"
    preamble = _multipart_preamble(fields, filename, content_type)
    epilogue = b"\r\n" + f"--{BOUNDARY}--".encode() + b"\r\n"
    file_size = apk.stat().st_size
    content_length = len(preamble) + file_size + len(epilogue)
    path = f"/bot{token}/sendDocument"
    last_err: Exception | None = None
    ctx = ssl.create_default_context()
    for attempt in range(1, RETRIES + 1):
        t0 = time.monotonic()
        conn: http.client.HTTPSConnection | None = None
        try:
            _log(
                f"sendDocument attempt {attempt}/{RETRIES}: "
                f"{apk} ({file_size} bytes), timeout={SEND_TIMEOUT}s/chunk"
            )
            conn = http.client.HTTPSConnection(HOST, timeout=SEND_TIMEOUT, context=ctx)
            conn.putrequest("POST", path)
            conn.putheader("Content-Type", f"multipart/form-data; boundary={BOUNDARY}")
            conn.putheader("Content-Length", str(content_length))
            conn.endheaders()
            conn.send(preamble)
            sent = 0
            with apk.open("rb") as handle:
                while True:
                    chunk = handle.read(CHUNK)
                    if not chunk:
                        break
                    conn.send(chunk)
                    sent += len(chunk)
                    if sent == file_size or sent % (8 * 1024 * 1024) < CHUNK:
                        _log(f"  uploaded {sent}/{file_size} bytes")
            conn.send(epilogue)
            resp = conn.getresponse()
            raw = resp.read()
            elapsed = time.monotonic() - t0
            if resp.status >= 400:
                _log_err(raw.decode("utf-8", "replace"))
                if resp.status in {429, 500, 502, 503, 504} and attempt < RETRIES:
                    last_err = OSError(f"HTTP {resp.status}")
                    time.sleep(5 * attempt)
                    continue
                raise SystemExit(f"Telegram sendDocument HTTP {resp.status}")
            _log(f"sendDocument ok in {elapsed:.1f}s (attempt {attempt})")
            return _parse(raw, "sendDocument")
        except SystemExit:
            raise
        except (TimeoutError, OSError, http.client.HTTPException) as exc:
            last_err = exc
            _log_err(
                f"sendDocument attempt {attempt}/{RETRIES} failed after "
                f"{time.monotonic() - t0:.1f}s: {exc}"
            )
            if attempt < RETRIES:
                time.sleep(5 * attempt)
                continue
            raise SystemExit(f"Telegram sendDocument failed after {RETRIES} attempts: {exc}") from exc
        finally:
            if conn is not None:
                try:
                    conn.close()
                except OSError:
                    pass
    raise SystemExit(f"Telegram sendDocument failed: {last_err}")


def main() -> None:
    token = os.environ["TG_BOT_TOKEN"]
    chat_id = os.environ["TG_CHANNEL_ID"]
    apk = Path(os.environ.get("APK_FILE", "AngelaBox-android.apk"))
    caption_file = Path("telegram-caption.txt")
    message_file = Path("telegram-message.txt")
    markup_file = Path("telegram-markup.json")
    document_only = os.environ.get("DOCUMENT_ONLY", "").lower() in {"1", "true", "yes"}
    skip_document = os.environ.get("SKIP_DOCUMENT", "").lower() in {"1", "true", "yes"}
    message_sent = False

    # Changelog first: a 40MB APK timeout must not hide the channel post.
    if not document_only:
        text = message_file.read_text(encoding="utf-8").strip()
        fields: dict = {
            "chat_id": chat_id,
            "text": text,
            "disable_web_page_preview": True,
        }
        if markup_file.is_file():
            fields["reply_markup"] = json.loads(markup_file.read_text(encoding="utf-8"))
        post_json(token, "sendMessage", fields)
        message_sent = True

    if skip_document:
        return
    if not apk.is_file() or apk.stat().st_size < 1_000_000:
        raise SystemExit(f"APK missing or too small: {apk}")
    caption = caption_file.read_text(encoding="utf-8").strip() if caption_file.is_file() else apk.name
    try:
        post_document(
            token,
            {
                "chat_id": chat_id,
                "caption": caption[:1024],
                "disable_content_type_detection": "true",
            },
            apk,
        )
    except SystemExit as exc:
        if message_sent:
            _log_err(
                f"warning: APK upload failed after changelog posted; "
                f"channel still has notes + download button ({exc})"
            )
            return
        raise


if __name__ == "__main__":
    main()
