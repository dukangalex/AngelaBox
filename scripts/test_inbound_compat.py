#!/usr/bin/env python3
"""Sandbox replica of inbound / special-outbound / rule-set URL migration."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JSDELIVR_HOST = "testingcf.jsdelivr.net"
JSDELIVR = re.compile(r"^https?://([^/]*jsdelivr\.net)/gh/(.+)$")
RAW = re.compile(r"^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
GH_RAW = re.compile(r"^https?://github\.com/([^/]+)/([^/]+)/raw/(.+)$")


def rewrite_url(url: str) -> str:
    trimmed = url.strip()
    m = JSDELIVR.match(trimmed)
    if m:
        host, rest = m.groups()
        if host.lower() == JSDELIVR_HOST:
            return trimmed
        return f"https://{JSDELIVR_HOST}/gh/{rest}"
    m = RAW.match(trimmed)
    if m:
        owner, repo, ref, path = m.groups()
        return f"https://{JSDELIVR_HOST}/gh/{owner}/{repo}@{ref}/{path}"
    m = GH_RAW.match(trimmed)
    if m:
        owner, repo, rest = m.groups()
        slash = rest.find("/")
        if slash > 0:
            ref, path = rest[:slash], rest[slash + 1 :]
            return f"https://{JSDELIVR_HOST}/gh/{owner}/{repo}@{ref}/{path}"
    return trimmed


def unique_tag(base: str, used: set[str]) -> str:
    if base not in used:
        return base
    n = 1
    while f"{base}-{n}" in used:
        n += 1
    return f"{base}-{n}"


def migrate_inbounds(root: dict) -> None:
    inbounds = root.get("inbounds") or []
    used = {str(ib.get("tag") or "").strip() for ib in inbounds if str(ib.get("tag") or "").strip()}
    extra = []
    for ib in inbounds:
        had_sniff = any(k in ib for k in ("sniff", "sniff_timeout", "sniff_override_destination"))
        strategy = str(ib.get("domain_strategy") or "").strip()
        had_udp = any(k in ib for k in ("udp_disable_domain_unmapping", "udp_connect", "udp_timeout"))
        if not had_sniff and not strategy and not had_udp:
            continue
        tag = str(ib.get("tag") or "").strip()
        if not tag:
            tag = unique_tag(f"{ib.get('type') or 'in'}-in", used)
            ib["tag"] = tag
            used.add(tag)
        if strategy:
            extra.append({"inbound": tag, "action": "resolve", "strategy": strategy})
            ib.pop("domain_strategy", None)
        sniff_on = bool(ib.get("sniff")) or "sniff_timeout" in ib or bool(ib.get("sniff_override_destination"))
        if sniff_on:
            rule = {"inbound": tag, "action": "sniff"}
            timeout = str(ib.get("sniff_timeout") or "").strip()
            if timeout:
                rule["timeout"] = timeout
            extra.append(rule)
        for k in ("sniff", "sniff_timeout", "sniff_override_destination"):
            ib.pop(k, None)
        if had_udp:
            rule = {"inbound": tag, "action": "route-options"}
            if "udp_disable_domain_unmapping" in ib:
                rule["udp_disable_domain_unmapping"] = bool(ib.pop("udp_disable_domain_unmapping"))
            if "udp_connect" in ib:
                rule["udp_connect"] = bool(ib.pop("udp_connect"))
            if "udp_timeout" in ib:
                rule["udp_timeout"] = ib.pop("udp_timeout")
            extra.append(rule)
    if extra:
        route = root.setdefault("route", {})
        route["rules"] = extra + list(route.get("rules") or [])


def migrate_special(root: dict) -> None:
    outs = root.get("outbounds") or []
    dns_tags, block_tags, keep = set(), set(), []
    for o in outs:
        typ = str(o.get("type") or "").lower()
        tag = str(o.get("tag") or "").strip()
        if typ == "dns":
            if tag:
                dns_tags.add(tag)
        elif typ == "block":
            if tag:
                block_tags.add(tag)
        else:
            keep.append(o)
    if not dns_tags and not block_tags:
        return
    for o in keep:
        lst = o.get("outbounds")
        if not isinstance(lst, list):
            continue
        o["outbounds"] = [x for x in lst if (x if isinstance(x, str) else "").strip() not in dns_tags | block_tags]
    root["outbounds"] = keep
    route = root.setdefault("route", {})

    def rewrite(rules):
        if not isinstance(rules, list):
            return
        for rule in rules:
            if not isinstance(rule, dict):
                continue
            rewrite(rule.get("rules"))
            ob = str(rule.get("outbound") or "").strip()
            if ob in dns_tags:
                rule.pop("outbound", None)
                rule.setdefault("action", "hijack-dns")
            elif ob in block_tags:
                rule.pop("outbound", None)
                rule.setdefault("action", "reject")

    rewrite(route.get("rules"))
    final = str(route.get("final") or "").strip()
    if final in block_tags:
        route.pop("final", None)
        route.setdefault("rules", []).append({"action": "reject"})
    elif final in dns_tags:
        route.pop("final", None)


def rewrite_sets(root: dict) -> None:
    sets = (root.get("route") or {}).get("rule_set") or []
    for item in sets:
        if not isinstance(item, dict):
            continue
        for key in ("url", "download_url"):
            cur = str(item.get(key) or "").strip()
            if cur:
                item[key] = rewrite_url(cur)


def strip_sniff_override(root: dict) -> None:
    def walk(rules):
        if not isinstance(rules, list):
            return
        for rule in rules:
            if not isinstance(rule, dict):
                continue
            walk(rule.get("rules"))
            if str(rule.get("action") or "") == "sniff":
                rule.pop("override_destination", None)

    walk((root.get("route") or {}).get("rules"))


def sanitize(root: dict) -> dict:
    migrate_inbounds(root)
    strip_sniff_override(root)
    migrate_special(root)
    rewrite_sets(root)
    return root


def main() -> int:
    errors: list[str] = []
    src = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt").read_text()
    inbound_src = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt").read_text()
    if "ConfigInboundCompat.apply" not in src:
        errors.append("ConfigCompat.sanitize must call ConfigInboundCompat.apply")
    for needle in ("migrateLegacyInbounds", "migrateSpecialOutbounds", "rewriteRuleSetUrls", "legacy inbound fields"):
        if needle not in inbound_src:
            errors.append(f"missing {needle}")
    if JSDELIVR_HOST not in inbound_src:
        errors.append("rule-set rewrite must use testingcf jsDelivr")

    inbound = sanitize(
        {
            "inbounds": [
                {
                    "type": "tun",
                    "sniff": True,
                    "sniff_timeout": "1s",
                    "sniff_override_destination": True,
                    "domain_strategy": "prefer_ipv4",
                }
            ],
            "route": {"rules": [{"protocol": "dns", "action": "hijack-dns"}]},
        }
    )
    ib = inbound["inbounds"][0]
    if ib.get("tag") != "tun-in":
        errors.append(f"missing inbound tag: {ib}")
    if any(k in ib for k in ("sniff", "sniff_timeout", "domain_strategy")):
        errors.append(f"legacy inbound fields survived: {ib}")
    rules = inbound["route"]["rules"]
    if rules[0].get("action") != "resolve" or rules[1].get("action") != "sniff":
        errors.append(f"sniff/resolve not prepended: {rules}")
    if rules[1].get("override_destination"):
        errors.append("sniff action must not emit override_destination (sing-box 1.14 rejects it)")
    if rules[2].get("action") != "hijack-dns":
        errors.append("original route rule lost")

    leftover = sanitize(
        {
            "route": {
                "rules": [
                    {"inbound": "tun-in", "action": "sniff", "override_destination": True}
                ]
            }
        }
    )
    if leftover["route"]["rules"][0].get("override_destination"):
        errors.append("leftover sniff override_destination was not stripped")

    special = sanitize(
        {
            "outbounds": [
                {"type": "direct", "tag": "direct"},
                {"type": "dns", "tag": "dns-out"},
                {"type": "block", "tag": "block"},
                {"type": "selector", "tag": "proxy", "outbounds": ["direct", "block"]},
            ],
            "route": {
                "rules": [
                    {"protocol": "dns", "outbound": "dns-out"},
                    {"domain_suffix": ".ads", "outbound": "block"},
                ],
                "final": "proxy",
            },
        }
    )
    tags = [o["tag"] for o in special["outbounds"]]
    if "dns-out" in tags or "block" in tags:
        errors.append(f"special outbounds not dropped: {tags}")
    selector = next(o for o in special["outbounds"] if o["tag"] == "proxy")
    if selector.get("outbounds") != ["direct"]:
        errors.append(f"selector still lists block: {selector}")
    rr = special["route"]["rules"]
    if rr[0].get("action") != "hijack-dns" or rr[1].get("action") != "reject":
        errors.append(f"special outbound rules: {rr}")

    urls = sanitize(
        {
            "route": {
                "rule_set": [
                    {
                        "tag": "geoip-cn",
                        "url": "https://raw.githubusercontent.com/Loyalsoldier/geoip/release/srs/cn.srs",
                    },
                    {
                        "tag": "geosite-cn",
                        "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
                    },
                    {
                        "tag": "ads",
                        "url": "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs",
                    },
                ]
            }
        }
    )
    got = [x["url"] for x in urls["route"]["rule_set"]]
    expect = [
        "https://testingcf.jsdelivr.net/gh/Loyalsoldier/geoip@release/srs/cn.srs",
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs",
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs",
    ]
    if got != expect:
        errors.append(f"url rewrite failed: {got}")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("inbound/ruleset compat sandbox ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
