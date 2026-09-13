package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCompatTest {
    @Test
    fun pluginOptsObjectBecomesString() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "shadowsocks")
                        .put("tag", "ss")
                        .put("plugin", "obfs-local")
                        .put("plugin-opts", JSONObject().put("mode", "http").put("host", "download.windowsupdate.com")),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val ss = out.getJSONArray("outbounds").getJSONObject(0)
        val opts = ss.getString("plugin_opts")
        assertTrue(opts.contains("obfs=http"))
        assertTrue(opts.contains("obfs-host=download.windowsupdate.com"))
        assertEquals(false, ss.has("plugin-opts"))
    }

    @Test
    fun sanitizeKeepsTlsEch() {
        val ech = JSONObject().put("enabled", true).put("query_server_name", "cover.example.com")
        val src = JSONObject().put(
            "outbounds",
            JSONArray().put(
                JSONObject()
                    .put("type", "vless")
                    .put("tag", "n")
                    .put("tls", JSONObject().put("enabled", true).put("server_name", "example.com").put("ech", ech)),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val kept = out.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getJSONObject("ech")
        assertEquals(true, kept.getBoolean("enabled"))
        assertEquals("cover.example.com", kept.getString("query_server_name"))
    }

    @Test
    fun stripsDnsDetourToEmptyDirect() {
        val src = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "udp")
                            .put("tag", "alidns")
                            .put("server", "223.5.5.5")
                            .put("detour", "direct"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val server = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals(false, server.has("detour"))
        assertEquals("223.5.5.5", server.getString("server"))
    }

    @Test
    fun keepsDnsDetourToProxy() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(JSONObject().put("type", "vless").put("tag", "proxy")),
            )
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "https")
                            .put("tag", "remote")
                            .put("server", "8.8.8.8")
                            .put("detour", "proxy"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val server = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("proxy", server.getString("detour"))
    }

    @Test
    fun stripsDnsDetourWhenOutboundMissing() {
        val src = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "vless").put("tag", "node")))
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("tag", "local").put("address", "223.5.5.5").put("detour", "direct"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        assertEquals(false, out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).has("detour"))
    }

    @Test
    fun keepsDetourToBoundDirect() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject().put("type", "direct").put("tag", "wlan").put("bind_interface", "wlan0"),
                ),
            )
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("type", "udp").put("tag", "local").put("server", "1.1.1.1")
                            .put("detour", "wlan"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        assertEquals(
            "wlan",
            out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"),
        )
    }

    @Test
    fun migratesLegacyFakeipObject() {
        val src = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(JSONObject().put("tag", "remote").put("address", "8.8.8.8"))
                            .put(JSONObject().put("tag", "fakeip").put("address", "fakeip")),
                    )
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject().put("query_type", JSONArray().put("A").put("AAAA"))
                                .put("server", "fakeip"),
                        ),
                    )
                    .put(
                        "fakeip",
                        JSONObject().put("enabled", true)
                            .put("inet4_range", "198.18.0.0/15")
                            .put("inet6_range", "fc00::/18"),
                    ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val dns = out.getJSONObject("dns")
        assertEquals(false, dns.has("fakeip"))
        val servers = dns.getJSONArray("servers")
        assertEquals("udp", servers.getJSONObject(0).getString("type"))
        assertEquals("8.8.8.8", servers.getJSONObject(0).getString("server"))
        val fake = servers.getJSONObject(1)
        assertEquals("fakeip", fake.getString("type"))
        assertEquals("fakeip", fake.getString("tag"))
        assertEquals("198.18.0.0/15", fake.getString("inet4_range"))
        assertEquals("fc00::/18", fake.getString("inet6_range"))
        assertEquals(false, fake.has("address"))
    }

    @Test
    fun migratesHttpsDoHAddress() {
        val src = JSONObject().put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "doh")
                        .put("address", "https://dns.google/dns-query")
                        .put("address_resolver", "bootstrap"),
                ),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val s = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("https", s.getString("type"))
        assertEquals("dns.google", s.getString("server"))
        assertEquals("bootstrap", s.getString("domain_resolver"))
        assertEquals(false, s.has("address"))
        assertEquals(false, s.has("path"))
    }

    @Test
    fun injectsFakeipServerWhenOnlyTopLevelObjectExists() {
        val src = JSONObject().put(
            "dns",
            JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("address", "1.1.1.1")))
                .put("fakeip", JSONObject().put("enabled", true).put("inet4_range", "198.18.0.0/15")),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val servers = out.getJSONObject("dns").getJSONArray("servers")
        assertEquals(2, servers.length())
        val fake = servers.getJSONObject(1)
        assertEquals("fakeip", fake.getString("type"))
        assertEquals("fakeip", fake.getString("tag"))
        val rule = out.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals("fakeip", rule.getString("server"))
    }

    @Test
    fun migratesTypedRcodeServerToRuleAction() {
        val src = JSONObject().put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(JSONObject().put("type", "udp").put("tag", "remote").put("server", "8.8.8.8"))
                        .put(
                            JSONObject()
                                .put("type", "rcode")
                                .put("tag", "dns-block")
                                .put("rcode", "success"),
                        ),
                )
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("domain_suffix", JSONArray().put("ads.example"))
                            .put("server", "dns-block"),
                    ),
                ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val dns = out.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        assertEquals(1, servers.length())
        assertEquals("remote", servers.getJSONObject(0).getString("tag"))
        val rule = dns.getJSONArray("rules").getJSONObject(0)
        assertEquals("predefined", rule.getString("action"))
        assertEquals("NOERROR", rule.getString("rcode"))
        assertEquals(false, rule.has("server"))
    }

    @Test
    fun migratesLegacyRcodeAddress() {
        val src = JSONObject().put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(JSONObject().put("tag", "remote").put("address", "1.1.1.1"))
                        .put(JSONObject().put("tag", "block").put("address", "rcode://refused")),
                )
                .put("final", "block"),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val dns = out.getJSONObject("dns")
        assertEquals(1, dns.getJSONArray("servers").length())
        assertEquals("remote", dns.getString("final"))
        val last = dns.getJSONArray("rules").getJSONObject(dns.getJSONArray("rules").length() - 1)
        assertEquals("predefined", last.getString("action"))
        assertEquals("REFUSED", last.getString("rcode"))
    }

    @Test
    fun migratesPredefinedServerType() {
        val src = JSONObject().put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("type", "https").put("tag", "doh").put("server", "dns.google"))
                    .put(
                        JSONObject()
                            .put("type", "predefined")
                            .put("tag", "nx")
                            .put("responses", JSONArray().put(JSONObject().put("rcode", "NXDOMAIN"))),
                    ),
            ).put(
                "rules",
                JSONArray().put(JSONObject().put("domain", JSONArray().put("blocked.test")).put("server", "nx")),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val servers = out.getJSONObject("dns").getJSONArray("servers")
        assertEquals(1, servers.length())
        assertEquals("doh", servers.getJSONObject(0).getString("tag"))
        val rule = out.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals("predefined", rule.getString("action"))
        assertEquals("NXDOMAIN", rule.getString("rcode"))
    }

    @Test
    fun migratesLegacyInboundSniffAndStrategy() {
        val src = JSONObject()
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("sniff", true)
                        .put("sniff_timeout", "1s")
                        .put("sniff_override_destination", true)
                        .put("domain_strategy", "prefer_ipv4"),
                ),
            )
            .put(
                "route",
                JSONObject().put(
                    "rules",
                    JSONArray().put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val inbound = out.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun-in", inbound.getString("tag"))
        assertEquals(false, inbound.has("sniff"))
        assertEquals(false, inbound.has("sniff_timeout"))
        assertEquals(false, inbound.has("sniff_override_destination"))
        assertEquals(false, inbound.has("domain_strategy"))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        val resolve = rules.getJSONObject(0)
        assertEquals("resolve", resolve.getString("action"))
        assertEquals("prefer_ipv4", resolve.getString("strategy"))
        assertEquals("tun-in", resolve.getString("inbound"))
        val sniff = rules.getJSONObject(1)
        assertEquals("sniff", sniff.getString("action"))
        assertEquals("1s", sniff.getString("timeout"))
        assertEquals(false, sniff.has("override_destination"))
        assertEquals("hijack-dns", rules.getJSONObject(2).getString("action"))
    }

    @Test
    fun stripsLeftoverSniffOverrideDestination() {
        val src = JSONObject()
            .put(
                "route",
                JSONObject().put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("inbound", "tun-in")
                            .put("action", "sniff")
                            .put("override_destination", true),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val sniff = out.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("sniff", sniff.getString("action"))
        assertEquals(false, sniff.has("override_destination"))
    }

    @Test
    fun dropsMissingRemoteRuleSetsAndDanglingRules() {
        val src = JSONObject()
            .put(
                "route",
                JSONObject()
                    .put(
                        "rule_set",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("tag", "geoip-cn")
                                    .put("type", "remote")
                                    .put("url", "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"),
                            )
                            .put(
                                JSONObject()
                                    .put("tag", "geoip-fastly")
                                    .put("type", "remote")
                                    .put("url", "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-fastly.srs"),
                            ),
                    )
                    .put(
                        "rules",
                        JSONArray()
                            .put(JSONObject().put("rule_set", "geoip-fastly").put("outbound", "direct"))
                            .put(JSONObject().put("rule_set", "geoip-cn").put("outbound", "direct")),
                    ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val sets = out.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(1, sets.length())
        assertEquals("geoip-cn", sets.getJSONObject(0).getString("tag"))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("geoip-cn", rules.getJSONObject(0).getString("rule_set"))
    }

    @Test
    fun migratesDnsAndBlockOutbounds() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(JSONObject().put("type", "dns").put("tag", "dns-out"))
                    .put(JSONObject().put("type", "block").put("tag", "block"))
                    .put(
                        JSONObject()
                            .put("type", "selector")
                            .put("tag", "proxy")
                            .put("outbounds", JSONArray().put("direct").put("block")),
                    ),
            )
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray()
                            .put(JSONObject().put("protocol", "dns").put("outbound", "dns-out"))
                            .put(JSONObject().put("domain_suffix", ".ads").put("outbound", "block")),
                    )
                    .put("final", "proxy"),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val tags = mutableListOf<String>()
        val outs = out.getJSONArray("outbounds")
        for (i in 0 until outs.length()) tags.add(outs.getJSONObject(i).getString("tag"))
        assertEquals(false, tags.contains("dns-out"))
        assertEquals(false, tags.contains("block"))
        val selector = outs.getJSONObject(1)
        assertEquals("proxy", selector.getString("tag"))
        assertEquals(1, selector.getJSONArray("outbounds").length())
        assertEquals("direct", selector.getJSONArray("outbounds").getString(0))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        assertEquals("hijack-dns", rules.getJSONObject(0).getString("action"))
        assertEquals(false, rules.getJSONObject(0).has("outbound"))
        assertEquals("reject", rules.getJSONObject(1).getString("action"))
    }

    @Test
    fun rewritesGithubRawRuleSetUrls() {
        val src = JSONObject().put(
            "route",
            JSONObject().put(
                "rule_set",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("tag", "geoip-cn")
                            .put("type", "remote")
                            .put("format", "binary")
                            .put(
                                "url",
                                "https://raw.githubusercontent.com/Loyalsoldier/geoip/release/srs/cn.srs",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("tag", "geosite-cn")
                            .put(
                                "url",
                                "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("tag", "category-ads-all")
                            .put(
                                "url",
                                "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("tag", "already-jsd")
                            .put(
                                "url",
                                "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs",
                            ),
                    ),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val sets = out.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/Loyalsoldier/geoip@release/srs/cn.srs",
            sets.getJSONObject(0).getString("url"),
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs",
            sets.getJSONObject(1).getString("url"),
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs",
            sets.getJSONObject(2).getString("url"),
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs",
            sets.getJSONObject(3).getString("url"),
        )
    }
}
