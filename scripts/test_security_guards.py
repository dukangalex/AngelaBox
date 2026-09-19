#!/usr/bin/env python3
"""Regression guards for AngelaBox security hardening."""
from __future__ import annotations

import ipaddress
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
RELEASE_CERT = "e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151"
LEAKED_CERT = "32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641"
KERNEL_COMMIT = "dbea8d3ae919844bc5114ca418c4a3e1deb67e24"
CN_RULE_SET_TOKENS = {
    "geoip-cn",
    "geosite-cn",
    "geosite-geolocation-cn",
    "geoip_cn",
    "geosite_cn",
    "cn",
}
UPDATE_HOSTS = {
    "github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
    "codeload.github.com",
}


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def texts_of(rule: dict, key: str) -> list[str]:
    raw = rule.get(key)
    if raw is None:
        return []
    if isinstance(raw, str):
        return [raw]
    if isinstance(raw, list):
        return [str(x) for x in raw]
    return []


def is_explicit_cn_rule_set(raw: str) -> bool:
    token = raw.strip().lower().rsplit("/", 1)[-1]
    return token in CN_RULE_SET_TOKENS


def is_cn_suffix(raw: str) -> bool:
    s = raw.strip().lower().lstrip(".")
    return s == "cn" or s.endswith(".cn")


def is_private_cidr(raw: str) -> bool:
    s = raw.strip()
    if not s:
        return False
    try:
        if "/" in s:
            net = ipaddress.ip_network(s, strict=False)
        else:
            net = ipaddress.ip_network(s + ("/32" if ":" not in s else "/128"), strict=False)
    except ValueError:
        return False
    return (
        net.network_address.is_private
        or net.network_address.is_loopback
        or net.network_address.is_link_local
        or net.network_address.is_unspecified
    )


def is_bypass_direct_rule(rule: dict) -> bool:
    sets = texts_of(rule, "rule_set") + texts_of(rule, "geosite") + texts_of(rule, "geoip")
    if any("!" in item for item in sets):
        return False
    suffixes = texts_of(rule, "domain_suffix")
    cidrs = texts_of(rule, "ip_cidr")
    extra = (
        texts_of(rule, "domain")
        + texts_of(rule, "domain_keyword")
        + texts_of(rule, "domain_regex")
        + texts_of(rule, "ip_cidr6")
    )
    if extra:
        return False
    if any(not is_explicit_cn_rule_set(item) for item in sets):
        return False
    if any(not is_cn_suffix(item) for item in suffixes):
        return False
    if any(not is_private_cidr(item) for item in cidrs):
        return False
    private_flag = bool(rule.get("ip_is_private", False))
    return private_flag or bool(sets) or bool(suffixes) or bool(cidrs)


def rebind_to_loopback(listen: str) -> str:
    s = listen.strip()
    if not s or s.startswith("/"):
        return s
    if s.startswith("["):
        end = s.find("]")
        if end < 0:
            return "127.0.0.1:9090"
        port = s[end + 1 :].lstrip(":") or "9090"
        return f"127.0.0.1:{port}"
    colon = s.rfind(":")
    if colon < 0:
        return "127.0.0.1:9090"
    port = s[colon + 1 :] or "9090"
    return f"127.0.0.1:{port}"


def require_update_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise ValueError("https only")
    host = (parsed.hostname or "").lower()
    if host not in UPDATE_HOSTS:
        raise ValueError("host not pinned")


def test_source_guards() -> None:
    compiler = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    assert "haystack.contains" not in compiler
    assert "isExplicitCnRuleSet" in compiler
    assert "isPrivateCidr" in compiler

    manifest = read("app/src/main/AndroidManifest.xml")
    assert 'android:allowBackup="false"' in manifest
    assert 'android:scheme="file"' not in manifest
    assert 'android:usesCleartextTraffic="false"' in manifest
    assert "network_security_config" in manifest

    checker = read("app/src/github/java/io/nekohasekai/sfa/vendor/GitHubUpdateChecker.kt")
    assert "LEGACY_RELEASES_URL" not in checker
    assert "dukangalex/ChainBox" not in checker
    assert "AngelaBox-android.apk" in checker
    get_text = checker.split("private fun getText", 1)[-1].split("private fun ", 1)[0]
    assert "Authorization" not in get_text

    release = read(".github/workflows/release-chainbox.yml")
    assert "AngelaBox-android.apk" in release
    assert "ChainBox-android.apk" not in release
    assert "windows-cli" in release
    assert "AngelaBox-windows-amd64.zip" in release
    assert "build_windows_cli.sh" in release
    assert RELEASE_CERT in release
    assert "steps.pin.outputs.commit" in release
    assert "build_libbox -target android -platform android/arm64 -debug" not in release
    assert "env -u GITHUB_TOKEN" in release
    assert "gomobile init" in release
    assert 'version || "$(go env GOPATH)/bin/gomobile" init' not in release

    props = read("version.properties")
    assert f"KERNEL_COMMIT={KERNEL_COMMIT}" in props
    assert "VERSION_NAME=1.0.62-beta" in props
    assert "VERSION_CODE=10062" in props
    assert "KERNEL_UPSTREAM=1.15.0-alpha.6" in props
    assert "KERNEL_TAG=v1.15.0-chain.2" in props

    gradle = read("app/build.gradle.kts")
    assert 'buildConfigField("String", "KERNEL_TAG"' in gradle
    assert 'buildConfigField("String", "KERNEL_UPSTREAM"' in gradle
    assert 'buildConfigField("String", "KERNEL_COMMIT"' in gradle

    core_id = read("app/src/main/java/io/nekohasekai/sfa/utils/CoreIdentity.kt")
    assert "fun libboxOrPin" in core_id
    assert "BuildConfig.KERNEL_TAG" in core_id
    assert "fun display" in core_id
    assert "Libbox.version()" in core_id

    assert "Stamp kernel version tag" in release
    assert 'git tag -f "$KTAG" HEAD' in release
    ci = read(".github/workflows/ci.yml")
    assert "Stamp kernel version tag" in ci
    assert 'git tag -f "$KTAG" HEAD' in ci
    assert "steps.pin.outputs.commit" in ci
    assert "5e1593f0f5bf1110a94aab71e55244ceb4ccceba" not in ci

    assert "公开说明" in read("README.md")
    assert "公开说明" in read("docs/SECURITY.md")
    assert "7736e1e" in read("docs/SECURITY.md")
    assert "不改写" in read("docs/SECURITY.md")
    assert "goodmen001" in read("docs/SECURITY.md")
    assert (ROOT / "docs/WINDOWS.md").is_file()
    assert (ROOT / "scripts/build_windows_cli.sh").is_file()
    desktop = read(".github/workflows/release-windows-desktop.yml")
    assert "io.chainbox.desktop" in desktop
    assert "AngelaBox-windows-" in desktop
    assert "refusing to publish official SFW" in desktop
    assert "WINDOWS_CERTIFICATES_P12" in desktop
    assert "signing.local.json" in desktop
    assert "New-SelfSignedCertificate" in desktop

    trust = read("app/src/main/java/io/nekohasekai/sfa/vendor/ReleaseTrust.kt")
    assert RELEASE_CERT in trust
    assert LEAKED_CERT in trust

    xposed = read("app/src/main/java/io/github/libxposed/service/XposedProvider.java")
    assert "isTrustedCaller" in xposed
    assert "getCallingUid" in xposed
    assert '"org.lsposed.manager"' in xposed
    assert '"org.lsposed.daemon"' in xposed
    assert 'startsWith("org.lsposed.")' not in xposed
    assert "android debug" in xposed

    backup = read("app/src/main/res/xml/backup_rules.xml")
    assert "profiles.db" not in backup
    cache = read("app/src/main/res/xml/cache_paths.xml")
    assert "external-files-path" not in cache

    quic = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    assert "Settings.excludeCnQuic && Settings.chinaDirect" in quic

    http = read("app/src/main/java/io/nekohasekai/sfa/utils/HTTPClient.kt")
    assert "RemoteUrlGuard.Kind" in http
    assert "Libbox.newHTTPClient" not in http
    assert "instanceFollowRedirects = false" in http
    assert "fun nextUrl" in http
    assert "require(conn is HttpsURLConnection)" in http
    assert "fun openPinned" in http
    assert "fun requestUrlOnIp" in http
    assert "fun headersForHop" in http
    assert "fun httpFailureMessage" in http
    assert 'URI("https", null, ipHost' not in http
    assert "PinnedSniSslSocketFactory" in http
    assert "SSLCertificateSocketFactory" in http
    assert "createSocket(peer, port)" in http

    guard = read("app/src/main/java/io/nekohasekai/sfa/utils/RemoteUrlGuard.kt")
    assert 'require(scheme == "https")' in guard
    assert "订阅仅允许 HTTP" not in guard
    assert "scheme == \"http\"" not in guard
    assert "无法解析主机，已拒绝" in guard
    assert "if (resolved.isEmpty())" in guard
    assert "fun requireHttpsPublic" in guard
    assert "fun embeddedIpv4" in guard
    assert "data class ValidatedEndpoint" in guard
    assert "resolved.all { isAddressAllowed(it, kind) }" in guard
    assert "if (isRfc1918(embedded) || isCgnat(embedded)) return false" in guard

    inbound = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt")
    assert "RemoteUrlGuard.isPublicHttpsUrl" in inbound
    assert 'rewritten = "https://" + rewritten.substring(7)' not in inbound

    dav = read("app/src/main/java/io/nekohasekai/sfa/utils/BackupManager.kt")
    assert "RemoteUrlGuard.requireAllowed" in dav
    assert "HTTPClient.openPinned" in dav
    assert "UPDATE remote_servers SET secret" in dav
    assert "url.openConnection()" not in dav
    assert "WebDAV 下载过大" in dav

    exporter = read("app/src/main/java/io/nekohasekai/sfa/bg/DebugInfoExporter.kt")
    assert "setReadable(true, true)" in exporter
    assert "setReadable(true, false)" not in exporter
    assert "fun redactSecrets" in exporter
    assert "MAX_LOG_BYTES" in exporter

    openconnect = read(
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/OpenConnectBrowserDialog.kt"
    )
    assert "fun matchesOpenConnectCallback" in openconnect
    assert "url.startsWith(it)" not in openconnect

    shares = read("app/src/main/java/io/nekohasekai/sfa/ktx/Shares.kt")
    assert "fun shareBasename" in shares
    assert "${profile.name}.bpf" not in shares

    ci = read(".github/workflows/ci.yml")
    assert "python3 scripts/test_security_guards.py" in ci
    assert "python3 scripts/test_apk_signing.py" in ci

    fdroid = read("app/src/main/java/io/nekohasekai/sfa/update/FDroidUpdateChecker.kt")
    assert "Libbox.checkFDroidUpdate" not in fdroid
    assert "return null" in fdroid

    browsers = read("app/src/main/java/io/nekohasekai/sfa/ktx/Browsers.kt")
    assert 'uri.scheme.equals("https"' in browsers

    gradle = read("app/build.gradle.kts")
    assert "taskGraph.whenReady" in gradle
    assert "wantsReleaseApk" in gradle
    assert "testOtherDebugUnitTest" in gradle

    gitignore = read(".gitignore")
    assert "*.jks" in gitignore
    assert "*.keystore" in gitignore
    assert "*.p12" in gitignore
    assert "*.pfx" in gitignore
    assert "signing.local.json" in gitignore


def test_bypass_mixed_or() -> None:
    assert is_bypass_direct_rule({"rule_set": ["geoip-cn"], "outbound": "direct"})
    assert is_bypass_direct_rule({"ip_is_private": True, "outbound": "direct"})
    assert is_bypass_direct_rule({"domain_suffix": [".cn"], "outbound": "direct"})
    assert is_bypass_direct_rule({"ip_cidr": ["10.0.0.0/8"], "outbound": "direct"})
    assert not is_bypass_direct_rule(
        {"rule_set": ["geoip-cn", "geosite-google"], "outbound": "direct"}
    )
    assert not is_bypass_direct_rule(
        {"geoip": "cn", "geosite": "google", "outbound": "direct"}
    )
    assert not is_bypass_direct_rule(
        {"domain_suffix": ["cn", "google.com"], "outbound": "direct"}
    )
    assert not is_bypass_direct_rule(
        {"domain_keyword": ["cn"], "outbound": "direct"}
    )
    assert not is_bypass_direct_rule(
        {"ip_cidr": ["1.1.1.1/32"], "outbound": "direct"}
    )
    assert not is_bypass_direct_rule(
        {"rule_set": ["geosite-geolocation-!cn"], "outbound": "direct"}
    )
    # haystack trap: "china" inside an unrelated matcher must not bypass
    assert not is_bypass_direct_rule(
        {"domain": ["www.china-airlines.com"], "outbound": "direct"}
    )


def test_update_url_pin() -> None:
    require_update_url("https://api.github.com/repos/dukangalex/AngelaBox/releases")
    require_update_url(
        "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk"
    )
    for bad in (
        "http://api.github.com/repos/dukangalex/AngelaBox/releases",
        "https://evil.example/AngelaBox-android.apk",
        "https://github.com.evil.tld/x",
        "https://dukangalex.github.io/malware.apk",
    ):
        try:
            require_update_url(bad)
        except ValueError:
            continue
        raise AssertionError(f"accepted {bad}")


def test_clash_api_loopback() -> None:
    assert rebind_to_loopback("0.0.0.0:9090") == "127.0.0.1:9090"
    assert rebind_to_loopback("[::]:9090") == "127.0.0.1:9090"
    assert rebind_to_loopback("192.168.1.8:9090") == "127.0.0.1:9090"
    assert rebind_to_loopback("127.0.0.1:9090") == "127.0.0.1:9090"


def test_china_direct_exact_tags() -> None:
    src = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChinaDirect.kt")
    assert 't.contains("geoip-cn")' not in src
    assert 't.contains("geosite-cn")' not in src
    assert 'token == "geoip-cn"' in src


def test_no_second_publisher() -> None:
    workflows = ROOT / ".github" / "workflows"
    publishers = []
    for path in workflows.glob("*.yml"):
        text = path.read_text(encoding="utf-8")
        if "action-gh-release" in text or "softprops/action-gh-release" in text:
            publishers.append(path.name)
    assert publishers == ["release-chainbox.yml"], publishers
    assert not (workflows / "build-chainbox.yml").exists()


def test_no_secret_files() -> None:
    forbidden_suffixes = {".jks", ".keystore", ".p12", ".pfx"}
    found = []
    skip_parts = {".git", "node_modules", ".gradle", "build", "__pycache__"}
    for path in ROOT.rglob("*"):
        if any(part in skip_parts for part in path.parts):
            continue
        if path.suffix.lower() in forbidden_suffixes:
            found.append(str(path.relative_to(ROOT)))
    assert found == [], found
    import subprocess

    listed = subprocess.check_output(
        ["git", "log", "--all", "--full-history", "--pretty=format:", "--", "*.jks", "*.keystore", "*.p12", "*.pfx"],
        cwd=ROOT,
        text=True,
    ).strip()
    assert listed == "", listed


def test_subscription_https_only() -> None:
    for bad in (
        "http://example.com/sub.yaml",
        "http://192.168.1.8:8080/clash.yaml",
        "http://127.0.0.1/secret",
    ):
        parsed = urlparse(bad)
        assert parsed.scheme == "http"
    src = read("app/src/main/java/io/nekohasekai/sfa/utils/RemoteUrlGuard.kt")
    assert 'Kind.SUBSCRIPTION -> require(scheme == "https"' not in src
    assert "http+https" not in src
    assert 'require(scheme == "https") { "仅允许 HTTPS" }' in src


def main() -> int:
    tests = [
        test_source_guards,
        test_bypass_mixed_or,
        test_update_url_pin,
        test_clash_api_loopback,
        test_china_direct_exact_tags,
        test_no_second_publisher,
        test_no_secret_files,
        test_subscription_https_only,
    ]
    failed = 0
    for test in tests:
        try:
            test()
            print(f"ok  {test.__name__}")
        except Exception as e:
            failed += 1
            print(f"FAIL {test.__name__}: {e}", file=sys.stderr)
    if failed:
        print(f"{failed} failed", file=sys.stderr)
        return 1
    print("all security guards passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
