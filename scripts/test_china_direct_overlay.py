#!/usr/bin/env python3
"""Sandbox replica of ConfigChinaDirect.apply / ConfigCompat.stripBrokenDnsDetours."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CN_DNS_TAG = "chainbox-cn-dns"
DIRECT_FALLBACK = "chainbox-direct"
LAN = ["local", "lan", "localhost", "home.arpa"]
CHINA_DNS_IPS = ["114.114.114.114/32", "223.5.5.5/32"]


def is_empty_direct(o: dict) -> bool:
    if str(o.get("type", "")).lower() != "direct":
        return False
    mark = o.get("routing_mark")
    if isinstance(mark, (int, float)) and mark != 0:
        return False
    if isinstance(mark, str) and mark not in ("", "0"):
        return False
    for k in (
        "override_address",
        "bind_interface",
        "inet4_bind_address",
        "inet6_bind_address",
        "inet4_address",
        "inet6_address",
    ):
        if str(o.get(k) or "").strip():
            return False
    port = o.get("override_port")
    if isinstance(port, (int, float)) and port != 0:
        return False
    return True


def strip_broken_dns_detours(root: dict) -> None:
    dns = root.get("dns")
    if not dns:
        return
    servers = dns.get("servers") or []
    outs = root.get("outbounds") or []
    tags = {o.get("tag") for o in outs if o.get("tag")}
    empty = {o.get("tag") for o in outs if o.get("tag") and is_empty_direct(o)}
    for server in servers:
        detour = str(server.get("detour") or "").strip()
        if not detour:
            continue
        if detour not in tags or detour in empty:
            server.pop("detour", None)
    dns["servers"] = servers


def drop_legacy_cn_dns(root: dict) -> None:
    dns = root.get("dns")
    if not dns:
        return
    servers = [s for s in dns.get("servers") or [] if s.get("tag") != CN_DNS_TAG]
    dns["servers"] = servers
    rules = [r for r in dns.get("rules") or [] if r.get("server") != CN_DNS_TAG]
    dns["rules"] = rules


def find_or_create_direct(outs: list) -> str:
    preferred = ["direct", "DIRECT", "直连"]
    for tag in preferred:
        for o in outs:
            if o.get("tag") == tag and str(o.get("type", "")).lower() == "direct":
                return tag
    for o in outs:
        if str(o.get("type", "")).lower() != "direct":
            continue
        tag = str(o.get("tag") or "").strip()
        if tag and tag.lower() not in {"dns", "block", "reject"}:
            return tag
    tag = "direct" if not any(o.get("tag") == "direct" for o in outs) else DIRECT_FALLBACK
    if not any(o.get("tag") == tag for o in outs):
        outs.append({"type": "direct", "tag": tag})
    return tag


def apply_china_direct(root: dict) -> dict:
    outs = root.setdefault("outbounds", [])
    direct = find_or_create_direct(outs)
    route = root.setdefault("route", {})
    injected = [
        {"ip_is_private": True, "outbound": direct},
        {"domain_suffix": LAN, "outbound": direct},
        {"ip_cidr": CHINA_DNS_IPS, "outbound": direct},
        {"domain": ["dns.alidns.com"], "outbound": direct},
        {"domain_suffix": ["cn", "qq.com"], "outbound": direct},
    ]
    route["rules"] = injected + list(route.get("rules") or [])
    drop_legacy_cn_dns(root)
    strip_broken_dns_detours(root)
    return root


def assert_true(cond: bool, msg: str, errors: list[str]) -> None:
    if not cond:
        errors.append(msg)


def main() -> int:
    errors: list[str] = []

    src = ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigChinaDirect.kt"
    text = src.read_text(encoding="utf-8")
    assert_true("applyCnDns" not in text, "Kotlin still has applyCnDns", errors)
    assert_true('.put("server", "223.5.5.5")' not in text, "Kotlin still injects 223.5.5.5 DNS", errors)
    assert_true("dropLegacyChinaDns" in text, "dropLegacyChinaDns missing", errors)
    assert_true("stripBrokenDnsDetours" in text, "stripBrokenDnsDetours missing", errors)

    crash = {
        "outbounds": [{"type": "direct", "tag": "direct"}],
        "dns": {
            "servers": [
                {
                    "type": "udp",
                    "tag": CN_DNS_TAG,
                    "server": "223.5.5.5",
                    "detour": "direct",
                },
                {
                    "type": "https",
                    "tag": "alidns",
                    "server": "223.5.5.5",
                    "detour": "direct",
                },
            ],
            "rules": [{"domain_suffix": ["cn"], "server": CN_DNS_TAG}],
        },
        "route": {"final": "proxy"},
    }
    out = apply_china_direct(json.loads(json.dumps(crash)))
    tags = [s.get("tag") for s in out["dns"]["servers"]]
    assert_true(CN_DNS_TAG not in tags, "legacy chainbox-cn-dns survived", errors)
    assert_true(tags == ["alidns"], f"unexpected dns servers {tags}", errors)
    assert_true("detour" not in out["dns"]["servers"][0], "empty-direct detour not stripped", errors)
    assert_true(out["dns"]["rules"] == [], "legacy cn dns rule survived", errors)
    first = out["route"]["rules"][0]
    assert_true(first.get("ip_is_private") is True, "lan rule missing", errors)
    assert_true(first.get("outbound") == "direct", "lan not pinned to direct", errors)
    cidrs = [r for r in out["route"]["rules"] if "ip_cidr" in r]
    assert_true(any("223.5.5.5/32" in r["ip_cidr"] for r in cidrs), "china dns ip bypass missing", errors)

    mixed = {
        "outbounds": [
            {"type": "selector", "tag": "direct", "outbounds": ["node"]},
            {"type": "vless", "tag": "node"},
        ]
    }
    out2 = apply_china_direct(mixed)
    tags2 = [o.get("tag") for o in out2["outbounds"]]
    assert_true(DIRECT_FALLBACK in tags2, "fallback direct not created", errors)
    assert_true(out2["route"]["rules"][0]["outbound"] == DIRECT_FALLBACK, "rules not using fallback", errors)

    chained = {
        "outbounds": [
            {"type": "selector", "tag": "proxy", "outbounds": ["node"]},
            {"type": "vless", "tag": "node"},
            {"type": "direct", "tag": "direct"},
            {"type": "chain", "tag": "chainbox-chain-1-2", "outbounds": ["proxy", "landing"]},
        ],
        "dns": {
            "servers": [
                {"type": "https", "tag": "remote", "server": "8.8.8.8", "detour": "proxy"},
            ]
        },
        "route": {"final": "chainbox-chain-1-2"},
    }
    out3 = apply_china_direct(json.loads(json.dumps(chained)))
    remote = out3["dns"]["servers"][0]
    assert_true(remote.get("detour") == "proxy", "proxy dns detour must stay", errors)
    assert_true(all(s.get("tag") != CN_DNS_TAG for s in out3["dns"]["servers"]), "injected cn dns", errors)
    assert_true(out3["route"]["final"] == "chainbox-chain-1-2", "chain final rewritten", errors)
    assert_true(out3["route"]["rules"][0]["outbound"] == "direct", "china rules not in front", errors)

    empty = {}
    out4 = apply_china_direct(empty)
    assert_true("dns" not in out4, "must not create dns section", errors)
    assert_true(out4["route"]["rules"][0]["ip_is_private"] is True, "force overlay missing", errors)

    webrtc = [
        {"network": "udp", "port": [3478, 19302, 5349], "action": "reject"},
        {"domain_keyword": ["stun.", "turn."], "action": "reject"},
    ]
    merged_rules = webrtc + out["route"]["rules"]
    assert_true(merged_rules[0].get("action") == "reject", "STUN reject must sit in front of China Direct", errors)
    assert_true(
        3478 in merged_rules[0].get("port", []),
        "UDP 3478 (bilibili/miwifi STUN) must be rejected before CN domain bypass",
        errors,
    )

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("china direct overlay sandbox ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
