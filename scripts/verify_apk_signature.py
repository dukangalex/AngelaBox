#!/usr/bin/env python3
"""Extract APK v2 signing cert SHA-256. Fail if it is not the AngelaBox release key."""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
V3_ID = 0xF05368C0
RELEASE_CERT_SHA256 = "e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151"
LEAKED_SFA_CERT_SHA256 = "32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641"


def _u32(buf: bytes, off: int) -> int:
    return struct.unpack_from("<I", buf, off)[0]


def _u64(buf: bytes, off: int) -> int:
    return struct.unpack_from("<Q", buf, off)[0]


def find_eocd(data: bytes) -> int:
    # EOCD is at the end; comment <= 65535.
    max_scan = min(len(data), 65557)
    for i in range(max_scan - 21, -1, -1):
        off = len(data) - max_scan + i if max_scan < len(data) else i
        if data[off : off + 4] == b"PK\x05\x06":
            return off
    raise ValueError("EOCD not found")


def extract_v2_certs(apk_path: Path) -> list[bytes]:
    data = apk_path.read_bytes()
    eocd = find_eocd(data)
    cd_off = _u32(data, eocd + 16)
    if cd_off < 32 or cd_off > len(data):
        raise ValueError("invalid central directory offset")
    # Signing block sits immediately before the CD. Footer: size2 (8) + magic (16)
    footer = data[cd_off - 24 : cd_off]
    if footer[8:] != APK_SIG_BLOCK_MAGIC:
        raise ValueError("APK Signing Block magic missing (not v2 signed?)")
    size2 = _u64(footer, 0)
    block_start = cd_off - 8 - size2
    if block_start < 0:
        raise ValueError("signing block start out of range")
    size1 = _u64(data, block_start)
    if size1 != size2:
        raise ValueError("signing block size mismatch")
    pairs = data[block_start + 8 : cd_off - 24]
    off = 0
    certs: list[bytes] = []
    while off + 12 <= len(pairs):
        pair_len = _u64(pairs, off)
        off += 8
        if pair_len < 4 or off + pair_len > len(pairs):
            break
        pair_id = _u32(pairs, off)
        value = pairs[off + 4 : off + pair_len]
        off += pair_len
        if pair_id != V2_ID:
            continue
        # value = length-prefixed sequence of signers
        so = 0
        if so + 4 > len(value):
            continue
        signers_len = _u32(value, so)
        so += 4
        signers_end = so + signers_len
        while so + 4 <= signers_end:
            signer_len = _u32(value, so)
            so += 4
            signer = value[so : so + signer_len]
            so += signer_len
            # signer: signed-data, signatures, public-key. We want certs inside signed-data.
            if len(signer) < 4:
                continue
            sd_len = _u32(signer, 0)
            signed = signer[4 : 4 + sd_len]
            # signed-data: digests, certificates, additional attributes
            if len(signed) < 4:
                continue
            digests_len = _u32(signed, 0)
            co = 4 + digests_len
            if co + 4 > len(signed):
                continue
            certs_len = _u32(signed, co)
            co += 4
            certs_end = co + certs_len
            while co + 4 <= certs_end:
                cert_len = _u32(signed, co)
                co += 4
                certs.append(signed[co : co + cert_len])
                co += cert_len
    if not certs:
        raise ValueError("no v2 certificates found")
    return certs


def _jar_manifest_sections(text: str) -> dict[str, dict[str, str]]:
    """Parse a JAR manifest, joining continuation lines (they start with a space)."""
    joined: list[str] = []
    buffer = ""
    for line in text.splitlines():
        if not line:
            if buffer:
                joined.append(buffer)
                buffer = ""
            continue
        if line.startswith(" "):
            buffer += line[1:]
        else:
            if buffer:
                joined.append(buffer)
            buffer = line
    if buffer:
        joined.append(buffer)
    sections: dict[str, dict[str, str]] = {}
    current: str | None = None
    for line in joined:
        if ": " not in line:
            continue
        key, value = line.split(": ", 1)
        if key == "Name":
            current = value.strip()
            sections[current] = {}
        elif current is not None:
            sections[current][key] = value.strip()
    return sections


def _jar_manifest_covers_all(archive) -> bool:
    import base64

    try:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except KeyError:
        return False
    sections = _jar_manifest_sections(manifest)
    for name in archive.namelist():
        if name.endswith("/") or name == "META-INF/MANIFEST.MF":
            continue
        if name.startswith("META-INF/") and name.endswith((".SF", ".RSA", ".DSA", ".EC")):
            continue
        section = sections.get(name)
        if section is None:
            return False
        expected = section.get("SHA-256-Digest") or section.get("SHA1-Digest")
        if expected is None:
            return False
        algo = hashlib.sha256 if "SHA-256-Digest" in section else hashlib.sha1
        if base64.b64encode(algo(archive.read(name)).digest()).decode() != expected:
            return False
    return True


def cert_sha256(cert: bytes) -> str:
    return hashlib.sha256(cert).hexdigest()


def _signing_block_ids(data: bytes) -> set[int]:
    eocd = find_eocd(data)
    cd_off = _u32(data, eocd + 16)
    footer = data[cd_off - 24 : cd_off]
    if footer[8:] != APK_SIG_BLOCK_MAGIC:
        return set()
    size2 = _u64(footer, 0)
    start = cd_off - 8 - size2
    pairs = data[start + 8 : cd_off - 24]
    off = 0
    ids: set[int] = set()
    while off + 12 <= len(pairs):
        pair_len = _u64(pairs, off)
        off += 8
        if pair_len < 4 or off + pair_len > len(pairs):
            break
        ids.add(_u32(pairs, off))
        off += pair_len
    return ids


def schemes(apk_path: Path) -> dict[str, bool]:
    """Which APK signature schemes this package carries.

    v1 matters beyond Android 6: installers that validate JAR signatures (some
    OEM ROMs) reject a v2-only package with INSTALL_PARSE_FAILED_NO_CERTIFICATES,
    which surfaces to the user as "该安装包未包含任何证书". v3 is what newer
    platform versions prefer.
    """
    import zipfile

    data = apk_path.read_bytes()
    archive = zipfile.ZipFile(apk_path)
    names = archive.namelist()
    has_sf = any(n.startswith("META-INF/") and n.endswith(".SF") for n in names)
    has_block = any(
        n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC")) for n in names
    )
    ids = _signing_block_ids(data)
    # apksigner reports "Verified using v1 scheme: false" whenever v2/v3 verify,
    # because it does not evaluate v1 in that case — so its line cannot be used
    # as evidence. Prove the JAR signature instead: META-INF/MANIFEST.MF must
    # cover every entry and every digest must match, which is exactly what a
    # JAR-only verifier (Android 6, some OEM installers) checks.
    v1 = has_sf and has_block and _jar_manifest_covers_all(archive)
    return {"v1": v1, "v2": V2_ID in ids, "v3": V3_ID in ids}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", nargs="+", type=Path)
    parser.add_argument("--require-release", action="store_true")
    parser.add_argument(
        "--require-schemes",
        action="store_true",
        help="fail unless the APK is signed with v1, v2 and v3",
    )
    args = parser.parse_args()
    status = 0
    for apk in args.apk:
        try:
            certs = extract_v2_certs(apk)
        except Exception as e:
            print(f"{apk}: ERROR {e}", file=sys.stderr)
            status = 1
            continue
        digest = cert_sha256(certs[0])
        carried = schemes(apk)
        label = " ".join(f"{name}={'yes' if ok else 'NO'}" for name, ok in carried.items())
        print(f"{apk}: {digest} [{label}]")
        if args.require_schemes and not all(carried.values()):
            missing = [name for name, ok in carried.items() if not ok]
            print(
                f"{apk}: missing signature scheme(s): {', '.join(missing)} — "
                "installers that require them will report no certificates",
                file=sys.stderr,
            )
            status = 1
        if digest == LEAKED_SFA_CERT_SHA256:
            print(f"{apk}: signed with leaked SagerNet JKS", file=sys.stderr)
            status = 1
        if args.require_release and digest != RELEASE_CERT_SHA256:
            print(f"{apk}: not the AngelaBox release cert (expected {RELEASE_CERT_SHA256})", file=sys.stderr)
            status = 1
    return status


if __name__ == "__main__":
    raise SystemExit(main())
