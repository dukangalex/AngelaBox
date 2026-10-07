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


def cert_sha256(cert: bytes) -> str:
    return hashlib.sha256(cert).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", nargs="+", type=Path)
    parser.add_argument("--require-release", action="store_true")
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
        print(f"{apk}: {digest}")
        if digest == LEAKED_SFA_CERT_SHA256:
            print(f"{apk}: signed with leaked SagerNet JKS", file=sys.stderr)
            status = 1
        if args.require_release and digest != RELEASE_CERT_SHA256:
            print(f"{apk}: not the AngelaBox release cert (expected {RELEASE_CERT_SHA256})", file=sys.stderr)
            status = 1
    return status


if __name__ == "__main__":
    raise SystemExit(main())
