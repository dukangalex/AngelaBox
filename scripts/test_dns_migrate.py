#!/usr/bin/env python3
"""Sandbox replica of ConfigCompat.migrateLegacyDns + migrateRcodeServers."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def apply_host(server: dict, typ: str, host_port: str) -> None:
    server["type"] = typ
    hp = host_port.strip()
    if hp.startswith("["):
        end = hp.find("]")
        if end > 0:
            server["server"] = hp[1:end]
            if end + 1 < len(hp) and hp[end + 1] == ":":
                try:
                    server["server_port"] = int(hp[end + 2 :])
                except ValueError:
                    pass
            return
    last = hp.rfind(":")
    first = hp.find(":")
    if last > 0 and last == first:
        try:
            server["server"] = hp[:last]
            server["server_port"] = int(hp[last + 1 :])
            return
        except ValueError:
            pass
    server["server"] = hp


def apply_url(server: dict, typ: str, rest: str) -> None:
    if "#" in rest:
        rest = rest.split("#", 1)[0]
    slash = rest.find("/")
    host_port = rest if slash < 0 else rest[:slash]
    path = "" if slash < 0 else rest[slash:]
    apply_host(server, typ, host_port)
    if path and path not in ("/", "/dns-query"):
        server["path"] = path


def canonicalize_rcode(raw: str) -> str:
    key = raw.strip().lower().replace("-", "_")
    return {
        "": "NOERROR",
        "success": "NOERROR",
        "noerror": "NOERROR",
        "no_error": "NOERROR",
        "0": "NOERROR",
        "format_error": "FORMERR",
        "formerr": "FORMERR",
        "form_err": "FORMERR",
        "1": "FORMERR",
        "server_failure": "SERVFAIL",
        "servfail": "SERVFAIL",
        "serv_fail": "SERVFAIL",
        "2": "SERVFAIL",
        "name_error": "NXDOMAIN",
        "nxdomain": "NXDOMAIN",
        "nx_domain": "NXDOMAIN",
        "3": "NXDOMAIN",
        "not_implemented": "NOTIMP",
        "notimp": "NOTIMP",
        "not_imp": "NOTIMP",
        "4": "NOTIMP",
        "refused": "REFUSED",
        "5": "REFUSED",
    }.get(key, raw.strip().upper())


def extract_rcode(server: dict) -> str | None:
    typ = str(server.get("type") or "").strip().lower()
    address = str(server.get("address") or "").strip()
    if typ == "rcode" or address.lower().startswith("rcode://"):
        if server.get("rcode"):
            raw = str(server["rcode"])
        elif address.lower().startswith("rcode://"):
            raw = address[8:]
        else:
            raw = "success"
        return canonicalize_rcode(raw)
    if typ != "predefined":
        return None
    if server.get("rcode"):
        return canonicalize_rcode(str(server["rcode"]))
    responses = server.get("responses") or []
    if responses and isinstance(responses[0], dict) and responses[0].get("rcode"):
        return canonicalize_rcode(str(responses[0]["rcode"]))
    return "NOERROR"


def migrate_server(server: dict, inet4, inet6) -> None:
    if "address_resolver" in server:
        server.setdefault("domain_resolver", server.pop("address_resolver"))
    typ = str(server.get("type") or "").strip()
    if typ and typ.lower() != "legacy":
        server.pop("address", None)
        return
    if "address" not in server:
        return
    address = str(server.pop("address")).strip()
    low = address.lower()
    if low == "local":
        server["type"] = "local"
    elif low == "fakeip":
        server["type"] = "fakeip"
        if inet4 and not server.get("inet4_range"):
            server["inet4_range"] = inet4
        if inet6 and not server.get("inet6_range"):
            server["inet6_range"] = inet6
    elif low.startswith("tcp://"):
        apply_host(server, "tcp", address[6:])
    elif low.startswith("tls://"):
        apply_host(server, "tls", address[6:])
    elif low.startswith("https://"):
        apply_url(server, "https", address[8:])
    elif low.startswith("h3://"):
        apply_url(server, "h3", address[5:])
    elif low.startswith("dhcp://"):
        server["type"] = "dhcp"
        iface = address[7:].strip()
        if iface and iface.lower() != "auto":
            server["interface"] = iface
    elif low.startswith("rcode://"):
        server["type"] = "rcode"
        server["rcode"] = address[8:].strip()
    else:
        apply_host(server, "udp", address)


def migrate_rcode(dns: dict) -> None:
    servers = dns.get("servers") or []
    rcode_by_tag: dict[str, str] = {}
    keep = []
    for s in servers:
        mapped = extract_rcode(s)
        if mapped is None:
            keep.append(s)
            continue
        tag = str(s.get("tag") or "").strip()
        if tag:
            rcode_by_tag[tag] = mapped
    if not rcode_by_tag:
        return
    if not keep:
        keep = [{"type": "local", "tag": "chainbox-dns-local"}]
    dns["servers"] = keep
    rules = dns.get("rules") or []
    rewritten = []
    for rule in rules:
        tag = str(rule.get("server") or "").strip()
        if tag in rcode_by_tag:
            rule = dict(rule)
            rule.pop("server", None)
            rule["action"] = "predefined"
            rule["rcode"] = rcode_by_tag[tag]
        rewritten.append(rule)
    final_tag = str(dns.get("final") or "").strip()
    if final_tag in rcode_by_tag:
        dns.pop("final", None)
        rewritten.append({"action": "predefined", "rcode": rcode_by_tag[final_tag]})
        fallback = str(keep[0].get("tag") or "")
        if fallback:
            dns["final"] = fallback
    dns["rules"] = rewritten


def migrate(root: dict) -> dict:
    dns = root.get("dns")
    if not dns:
        return root
    fake = dns.pop("fakeip", None)
    inet4 = inet6 = None
    enabled = False
    if isinstance(fake, dict):
        enabled = fake.get("enabled", True)
        inet4 = fake.get("inet4_range") or None
        inet6 = fake.get("inet6_range") or None
        if enabled:
            inet4 = inet4 or "198.18.0.0/15"
            inet6 = inet6 or "fc00::/18"
    servers = dns.setdefault("servers", [])
    has_fake = False
    for s in servers:
        migrate_server(s, inet4, inet6)
        if s.get("type") == "fakeip":
            has_fake = True
            if inet4 and not s.get("inet4_range"):
                s["inet4_range"] = inet4
            if inet6 and not s.get("inet6_range"):
                s["inet6_range"] = inet6
    if enabled and not has_fake:
        fake_s = {"type": "fakeip", "tag": "fakeip"}
        if inet4:
            fake_s["inet4_range"] = inet4
        if inet6:
            fake_s["inet6_range"] = inet6
        servers.append(fake_s)
        has_fake = True
    if has_fake and enabled:
        rules = dns.setdefault("rules", [])
        if not any(r.get("server") == "fakeip" for r in rules):
            dns["rules"] = [{"query_type": ["A", "AAAA"], "server": "fakeip"}] + rules
    migrate_rcode(dns)
    return root


def main() -> int:
    errors: list[str] = []
    src = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt").read_text()
    if "migrateLegacyDns" not in src:
        errors.append("migrateLegacyDns missing")
    if "legacy DNS fakeip" not in src:
        errors.append("must document 1.14 fakeip removal")
    if "migrateRcodeServers" not in src:
        errors.append("migrateRcodeServers missing")
    inbound = (ROOT / "app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt").read_text()
    if "ConfigInboundCompat.apply" not in src:
        errors.append("ConfigCompat.sanitize must call ConfigInboundCompat.apply")
    if "migrateLegacyInbounds" not in inbound:
        errors.append("migrateLegacyInbounds missing")
    if "rewriteRuleSetUrls" not in inbound:
        errors.append("rewriteRuleSetUrls missing")
    if "migrateSpecialOutbounds" not in inbound:
        errors.append("migrateSpecialOutbounds missing")

    crash = {
        "dns": {
            "servers": [
                {"tag": "remote", "address": "8.8.8.8"},
                {"tag": "fakeip", "address": "fakeip"},
            ],
            "rules": [{"query_type": ["A", "AAAA"], "server": "fakeip"}],
            "fakeip": {
                "enabled": True,
                "inet4_range": "198.18.0.0/15",
                "inet6_range": "fc00::/18",
            },
        }
    }
    out = migrate(json.loads(json.dumps(crash)))
    if "fakeip" in out["dns"] and isinstance(out["dns"]["fakeip"], dict):
        errors.append("top-level dns.fakeip survived")
    remote, fake = out["dns"]["servers"]
    if remote.get("type") != "udp" or remote.get("server") != "8.8.8.8":
        errors.append(f"udp migrate failed: {remote}")
    if fake.get("type") != "fakeip" or fake.get("inet4_range") != "198.18.0.0/15":
        errors.append(f"fakeip migrate failed: {fake}")
    if "address" in fake:
        errors.append("address leftover on fakeip server")

    doh = migrate(
        {
            "dns": {
                "servers": [
                    {
                        "address": "https://dns.google/dns-query",
                        "address_resolver": "boot",
                    }
                ]
            }
        }
    )
    s = doh["dns"]["servers"][0]
    if s.get("type") != "https" or s.get("server") != "dns.google":
        errors.append(f"doh migrate failed: {s}")
    if s.get("domain_resolver") != "boot" or "address_resolver" in s:
        errors.append(f"address_resolver not mapped: {s}")
    if s.get("path"):
        errors.append("default /dns-query should be omitted")

    only = migrate(
        {
            "dns": {
                "servers": [{"address": "1.1.1.1"}],
                "fakeip": {"enabled": True, "inet4_range": "198.18.0.0/15"},
            }
        }
    )
    types = [x.get("type") for x in only["dns"]["servers"]]
    if "fakeip" not in types:
        errors.append("did not inject typed fakeip server")
    if only["dns"]["rules"][0].get("server") != "fakeip":
        errors.append("missing A/AAAA fakeip rule")

    rcode = migrate(
        {
            "dns": {
                "servers": [
                    {"type": "udp", "tag": "remote", "server": "8.8.8.8"},
                    {"type": "rcode", "tag": "dns-block", "rcode": "success"},
                ],
                "rules": [{"domain_suffix": ["ads.example"], "server": "dns-block"}],
            }
        }
    )
    tags = [x.get("tag") for x in rcode["dns"]["servers"]]
    if "dns-block" in tags:
        errors.append("typed rcode server was not dropped")
    rule = rcode["dns"]["rules"][0]
    if rule.get("action") != "predefined" or rule.get("rcode") != "NOERROR":
        errors.append(f"rcode rule rewrite failed: {rule}")
    if "server" in rule:
        errors.append("rcode rule still has server field")

    addr = migrate(
        {
            "dns": {
                "servers": [
                    {"tag": "remote", "address": "1.1.1.1"},
                    {"tag": "block", "address": "rcode://refused"},
                ],
                "final": "block",
            }
        }
    )
    if any(x.get("type") == "rcode" for x in addr["dns"]["servers"]):
        errors.append("rcode:// leftover as type=rcode")
    last = addr["dns"]["rules"][-1]
    if last.get("action") != "predefined" or last.get("rcode") != "REFUSED":
        errors.append(f"rcode:// final rewrite failed: {last}")
    if addr["dns"].get("final") != "remote":
        errors.append(f"dns.final not remapped: {addr['dns'].get('final')}")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("dns migrate sandbox ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
