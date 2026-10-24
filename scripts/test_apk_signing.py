#!/usr/bin/env python3
"""Synthetic APK Signing Block parser + source guards for in-app update."""
from __future__ import annotations

import hashlib
import importlib.util
import struct
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def test_apk_is_signed_with_every_scheme() -> None:
    """A v2-only APK is uninstallable on Android 6 and on OEM installers that
    validate JAR signatures: they report INSTALL_PARSE_FAILED_NO_CERTIFICATES,
    which the user sees as "该安装包未包含任何证书". 1.0.130-beta shipped that
    way (minSdk 24 makes apksigner skip v1 by default)."""
    gradle = read("app/build.gradle.kts")
    for flag in ("enableV1Signing = true", "enableV2Signing = true", "enableV3Signing = true"):
        if flag not in gradle:
            raise AssertionError(f"release signing must set '{flag}'")
    for wf in (".github/workflows/release-chainbox.yml", ".github/workflows/release-stable.yml"):
        text = read(wf)
        if "Verified using v1 scheme (JAR signing): true" not in text:
            raise AssertionError(f"{wf} must refuse a package without a v1 signature")
        if "Verified using v3 scheme (APK Signature Scheme v3): true" not in text:
            raise AssertionError(f"{wf} must refuse a package without a v3 signature")
        if "--require-schemes" not in text:
            raise AssertionError(f"{wf} must run verify_apk_signature.py --require-schemes")


def load_verify():
    spec = importlib.util.spec_from_file_location(
        "verify_apk_signature", ROOT / "scripts" / "verify_apk_signature.py"
    )
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


def u32(n: int) -> bytes:
    return struct.pack("<I", n)


def u64(n: int) -> bytes:
    return struct.pack("<Q", n)


def u32pref(payload: bytes) -> bytes:
    return u32(len(payload)) + payload


def signer_value(cert: bytes) -> bytes:
    digests = u32pref(b"")
    certs = u32pref(u32pref(cert))
    signed = u32pref(digests + certs)
    signer = u32pref(signed)
    return u32pref(signer)


def pair(pair_id: int, value: bytes) -> bytes:
    body = u32(pair_id) + value
    return u64(len(body)) + body


def write_synthetic_apk(path: Path, cert: bytes) -> None:
    pairs = pair(0x7109871A, signer_value(cert))
    size = len(pairs) + 24
    block = u64(size) + pairs + u64(size) + b"APK Sig Block 42"
    prefix = b"\x11" * 16
    cd_off = len(prefix) + len(block)
    eocd = b"PK\x05\x06" + b"\x00" * 12 + u32(cd_off) + b"\x00\x00"
    path.write_bytes(prefix + block + eocd)


def test_synthetic_v2() -> None:
    mod = load_verify()
    cert = bytes(range(48))
    with tempfile.TemporaryDirectory() as tmp:
        apk = Path(tmp) / "a.apk"
        write_synthetic_apk(apk, cert)
        found = mod.extract_v2_certs(apk)
        assert found == [cert], found
        digest = hashlib.sha256(found[0]).hexdigest()
        assert len(digest) == 64


def test_source_guards() -> None:
    downloader = read("app/src/github/java/io/nekohasekai/sfa/vendor/ApkDownloader.kt")
    parser = read("app/src/main/java/io/nekohasekai/sfa/utils/ApkSigningCerts.kt")
    errors: list[str] = []
    if "ApkSigningCerts.firstCertDer" not in downloader:
        errors.append("ApkDownloader must pin certs via ApkSigningCerts.firstCertDer")
    if "APK has no signing certificate" in downloader:
        errors.append("in-app update must not show the English Samsung PM failure")
    if "安装包没有签名证书" not in downloader:
        errors.append("in-app update must report missing certs in Chinese")
    if "GET_SIGNING_CERTIFICATES" not in downloader:
        errors.append("PackageManager remains a fallback for signing certs")
    if "signingCertificateHistory" not in downloader:
        errors.append("fallback must also read signingCertificateHistory")
    if "0x7109871A" not in parser and "7109871A" not in parser:
        errors.append("ApkSigningCerts must parse APK v2 block id 0x7109871A")
    if "APK Sig Block 42" not in parser:
        errors.append("ApkSigningCerts must match APK Sig Block 42 magic")
    if "fun firstCertDer" not in parser:
        errors.append("ApkSigningCerts.firstCertDer missing")
    kotlin_test = read("app/src/test/java/io/nekohasekai/sfa/utils/ApkSigningCertsTest.kt")
    if "writeSyntheticApk" not in kotlin_test:
        errors.append("JVM unit test must cover a synthetic APK Signing Block")
    if errors:
        raise AssertionError("\n".join(errors))


def main() -> int:
    try:
        test_synthetic_v2()
        test_source_guards()
    except Exception as e:
        print("FAIL", e)
        return 1
    print("apk signing parser ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
