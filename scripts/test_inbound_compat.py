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


def drop_missing_rulesets(root: dict) -> None:
    unreplaceable = {
        "geoip-private.srs",
        "geoip-fastly.srs",
        "geoip-cloudfront.srs",
    }
    aliases = {
        "telegram.srs": "geosite-telegram.srs",
        "github.srs": "geosite-github.srs",
        "google.srs": "geosite-google.srs",
        "gitlab.srs": "geosite-gitlab.srs",
        "telegram-ip.srs": "geosite-telegram.srs",
        "geoip-telegram.srs": "geosite-telegram.srs",
        "geoip-google.srs": "geosite-google.srs",
        "geoip-netflix.srs": "geosite-netflix.srs",
        "geoip-facebook.srs": "geosite-facebook.srs",
        "geoip-twitter.srs": "geosite-twitter.srs",
        "geoip-cloudflare.srs": "geosite-cloudflare.srs",
        "geosite-biliintl.srs": "geosite-bilibili.srs",
        "geosite-apple-cn.srs": "geosite-apple@cn.srs",
        "geosite-tracker.srs": "geosite-category-ads-all.srs",
        "category-ai!cn.srs": "geosite-category-ai-!cn.srs",
    }
    geosite_base = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/"
    route = root.get("route") or {}
    sets = route.get("rule_set") or []
    drop = set()
    keep = []
    for item in sets:
        if not isinstance(item, dict):
            continue
        url_key = "url" if str(item.get("url") or "").strip() else "download_url"
        url = str(item.get(url_key) or "").strip()
        file = url.rsplit("/", 1)[-1].split("?", 1)[0].lower()
        tag = str(item.get("tag") or "").strip()
        remote = str(item.get("type") or "").lower() == "remote" or url.startswith("http")
        if not remote:
            keep.append(item)
            continue
        mapped = aliases.get(file)
        stem = file[:-4] if file.endswith(".srs") else file
        if mapped is None and not stem.startswith("geosite-") and not stem.startswith("geoip-"):
            mapped = aliases.get(f"{stem}.srs")
        if mapped:
            item[url_key] = geosite_base + mapped
            keep.append(item)
            continue
        if remote and file in unreplaceable:
            if tag:
                drop.add(tag)
            continue
        keep.append(item)
    route["rule_set"] = keep
    if drop:
        root["route"] = route

        def walk(rules):
            if not isinstance(rules, list):
                return
            kept_rules = []
            for rule in rules:
                if not isinstance(rule, dict):
                    continue
                walk(rule.get("rules"))
                rs = rule.get("rule_set")
                if isinstance(rs, str) and rs.strip() in drop:
                    continue
                if isinstance(rs, list):
                    leftover = [x for x in rs if str(x).strip() not in drop]
                    if not leftover:
                        continue
                    rule["rule_set"] = leftover[0] if len(leftover) == 1 else leftover
                kept_rules.append(rule)
            rules[:] = kept_rules

        walk(route.get("rules"))
        walk((root.get("dns") or {}).get("rules"))


def heal_direct_override(root: dict) -> None:
    outs = root.get("outbounds") or []
    for o in outs:
        if str(o.get("type") or "").lower() != "direct":
            continue
        addr = str(o.get("override_address") or "").strip()
        port = o.get("override_port") or 0
        try:
            port = int(port)
        except (TypeError, ValueError):
            port = 0
        if not addr and port == 0:
            continue
        o.pop("override_address", None)
        o.pop("override_port", None)
        tag = str(o.get("tag") or "").lower()
        a = addr.lower()
        if any(k in tag for k in ("reject", "block", "blackhole")) or a in (
            "240.0.0.1",
            "0.0.0.0",
            "127.0.0.1",
            "::1",
        ):
            o["type"] = "socks"
            o["server"] = "127.0.0.1"
            o["server_port"] = 9


def sanitize(root: dict) -> dict:
    migrate_inbounds(root)
    strip_sniff_override(root)
    heal_direct_override(root)
    migrate_special(root)
    rewrite_sets(root)
    drop_missing_rulesets(root)
    heal_download_clients(root)
    heal_missing_outbound_refs(root)
    ensure_hijack_dns(root)
    return root


HTTP_DIRECT_TAG = "angela-http-direct"


def outbound_tags(root: dict) -> set[str]:
    return {str(o.get("tag") or "").strip() for o in (root.get("outbounds") or []) if str(o.get("tag") or "").strip()}


def heal_download_clients(root: dict) -> None:
    tags = outbound_tags(root)
    clients = root.setdefault("http_clients", [])
    for client in clients:
        detour = str(client.get("detour") or "").strip()
        if detour and detour not in tags:
            client.pop("detour", None)
    safe = next((str(c.get("tag") or "").strip() for c in clients if str(c.get("tag") or "").strip() and not str(c.get("detour") or "").strip()), "")
    if not safe:
        safe = HTTP_DIRECT_TAG
        if not any(str(c.get("tag") or "") == safe for c in clients):
            clients.append({"tag": safe})
    route = root.setdefault("route", {})
    current = str(route.get("default_http_client") or "").strip()
    if not current or not any(str(c.get("tag") or "") == current for c in clients):
        route["default_http_client"] = safe
    for item in route.get("rule_set") or []:
        download = str(item.get("download_detour") or "").strip()
        if download and download not in tags:
            item.pop("download_detour", None)
            item["http_client"] = safe


def heal_missing_outbound_refs(root: dict) -> None:
    tags = outbound_tags(root)
    if not tags:
        return
    selector = None
    for o in root.get("outbounds") or []:
        t = str(o.get("type") or "").lower()
        tag = str(o.get("tag") or "").strip()
        if t in ("selector", "urltest") and tag and selector is None:
            selector = tag
        members = o.get("outbounds")
        if isinstance(members, list):
            o["outbounds"] = [m for m in members if (m if isinstance(m, str) else str((m or {}).get("tag") or "")) in tags]
    route = root.get("route") or {}
    final = str(route.get("final") or "").strip()
    if final and final not in tags:
        if selector:
            route["final"] = selector
        else:
            route.pop("final", None)
    for rule in route.get("rules") or []:
        ob = str(rule.get("outbound") or "").strip()
        if ob and ob not in tags:
            if selector:
                rule["outbound"] = selector
            else:
                rule.pop("outbound", None)


def ensure_hijack_dns(root: dict) -> None:
    route = root.setdefault("route", {})
    rules = list(route.get("rules") or [])
    if any(str(r.get("action") or "") == "hijack-dns" for r in rules if isinstance(r, dict)):
        return
    extra = [
        {"protocol": "dns", "action": "hijack-dns"},
        {"port": 53, "network": ["udp", "tcp"], "action": "hijack-dns"},
    ]
    route["rules"] = extra + rules


def main() -> int:
    errors: list[str] = []
    src = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt").read_text()
    inbound_src = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt").read_text()
    if "ConfigInboundCompat.apply" not in src:
        errors.append("ConfigCompat.sanitize must call ConfigInboundCompat.apply")
    for needle in ("migrateLegacyInbounds", "migrateSpecialOutbounds", "rewriteRuleSetUrls", "dropMissingRemoteRuleSets", "healDirectDestinationOverride", "ensureHijackDns", "legacy inbound fields"):
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

    missing_dns = sanitize({"route": {"rules": [{"ip_is_private": True, "outbound": "direct"}]}})
    if missing_dns["route"]["rules"][0].get("action") != "hijack-dns":
        errors.append(f"hijack-dns must be injected when missing: {missing_dns['route']['rules']}")

    healed = sanitize(
        {
            "outbounds": [
                {
                    "type": "direct",
                    "tag": "REJECT-DROP",
                    "override_address": "240.0.0.1",
                    "override_port": 1,
                },
                {
                    "type": "direct",
                    "tag": "direct-dns",
                    "override_address": "1.1.1.1",
                    "override_port": 53,
                },
            ]
        }
    )
    drop = healed["outbounds"][0]
    dns = healed["outbounds"][1]
    if drop.get("type") != "socks" or drop.get("server_port") != 9 or "override_address" in drop:
        errors.append(f"REJECT-DROP blackhole must become socks sink: {drop}")
    if dns.get("type") != "direct" or "override_address" in dns or "override_port" in dns:
        errors.append(f"non-blackhole direct override must only be stripped: {dns}")

    dropped = sanitize(
        {
            "route": {
                "rule_set": [
                    {
                        "tag": "geoip-cn",
                        "type": "remote",
                        "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs",
                    },
                    {
                        "tag": "geoip-fastly",
                        "type": "remote",
                        "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-fastly.srs",
                    },
                ],
                "rules": [
                    {"rule_set": "geoip-fastly", "outbound": "direct"},
                    {"rule_set": "geoip-cn", "outbound": "direct"},
                ],
            }
        }
    )
    tags = [x.get("tag") for x in dropped["route"]["rule_set"]]
    if tags != ["geoip-cn"]:
        errors.append(f"missing remote rule-set not dropped: {tags}")
    kept_rules = [r.get("rule_set") for r in dropped["route"]["rules"] if r.get("action") != "hijack-dns"]
    if kept_rules != ["geoip-cn"]:
        errors.append(f"dangling rule-set refs survived: {kept_rules}")

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

    healed = sanitize(
        {
            "outbounds": [
                {"type": "direct", "tag": "direct"},
                {"type": "selector", "tag": "节点选择", "outbounds": ["a"]},
                {"type": "shadowsocks", "tag": "a", "server": "1.1.1.1", "server_port": 1},
            ],
            "http_clients": [{"tag": "down", "detour": "proxy-select"}],
            "route": {
                "final": "proxy-select",
                "default_http_client": "down",
                "rule_set": [
                    {
                        "tag": "geosite-icloud",
                        "type": "remote",
                        "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-icloud.srs",
                        "download_detour": "proxy-select",
                    }
                ],
                "rules": [{"rule_set": "geosite-icloud", "outbound": "proxy-select"}],
            },
        }
    )
    if healed["http_clients"][0].get("detour") == "proxy-select":
        errors.append("http_clients detour to missing proxy-select was not stripped")
    if healed["route"].get("default_http_client") not in {c.get("tag") for c in healed["http_clients"]}:
        errors.append("default_http_client must point at a real http client")
    icloud = healed["route"]["rule_set"][0]
    if icloud.get("download_detour") == "proxy-select":
        errors.append("broken download_detour must be removed")
    if healed["route"].get("final") == "proxy-select":
        errors.append("route.final must not keep a missing outbound")
    if healed["route"]["rules"][0].get("outbound") == "proxy-select":
        errors.append("route rule outbound must not keep a missing tag")

    inbound_src = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt").read_text()
    if "fun healDownloadClients" not in inbound_src or "fun healMissingOutboundRefs" not in inbound_src:
        errors.append("ConfigInboundCompat must heal 1.14 http_clients and leftover outbound refs")
    if "angela-http-direct" not in inbound_src:
        errors.append("heal must inject a no-detour HTTP client for rule-set downloads")

    if inbound_src.find("fun healRemoteRuleSets") < 0:
        errors.append("ConfigInboundCompat must heal 404 rule-sets by replacing URLs")
    if "replaceRemoteRuleSetsMatching" not in inbound_src:
        errors.append("ConfigInboundCompat must replace the rule-set named in a kernel 404")
    if "needle in blob" in inbound_src:
        errors.append("rule-set matching must not substring-match the URL")

    replaced = sanitize(
        {
            "route": {
                "rule_set": [
                    {
                        "tag": "telegram",
                        "type": "remote",
                        "url": "https://raw.githubusercontent.com/foo/bar/main/telegram.srs",
                    },
                    {
                        "tag": "geoip-telegram",
                        "type": "remote",
                        "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-telegram.srs",
                    },
                    {
                        "tag": "geoip-cn",
                        "type": "remote",
                        "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs",
                    },
                ],
                "rules": [
                    {"rule_set": "telegram", "outbound": "proxy"},
                    {"rule_set": "geoip-telegram", "outbound": "proxy"},
                    {"rule_set": "geoip-cn", "outbound": "direct"},
                ],
            }
        }
    )
    by_tag = {x["tag"]: x["url"] for x in replaced["route"]["rule_set"]}
    official_tg = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-telegram.srs"
    if by_tag.get("telegram") != official_tg:
        errors.append(f"short telegram.srs must become official geosite-telegram: {by_tag.get('telegram')}")
    if by_tag.get("geoip-telegram") != official_tg:
        errors.append(f"missing geoip-telegram must become geosite-telegram: {by_tag.get('geoip-telegram')}")
    if "geoip-cn" not in by_tag:
        errors.append("working geoip-cn must be kept")
    refs = [r.get("rule_set") for r in replaced["route"]["rules"] if r.get("action") != "hijack-dns"]
    if "telegram" not in refs or "geoip-telegram" not in refs:
        errors.append(f"replaced rule-sets must keep original tags in routes: {refs}")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("inbound/ruleset compat sandbox ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
