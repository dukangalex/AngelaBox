package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val resolve = ruleWithAction(rules, "resolve")
        assertEquals("prefer_ipv4", resolve.getString("strategy"))
        assertEquals("tun-in", resolve.getString("inbound"))
        val sniff = ruleWithAction(rules, "sniff")
        assertEquals("1s", sniff.getString("timeout"))
        assertEquals(false, sniff.has("override_destination"))
        assertEquals("tun-in", sniff.getString("inbound"))
        ruleWithAction(rules, "hijack-dns")
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
        val sniff = ruleWithAction(out.getJSONObject("route").getJSONArray("rules"), "sniff")
        assertEquals(false, sniff.has("override_destination"))
    }

    @Test
    fun keepsReferencedRemoteRuleSetsAndTheirRules() {
        // Design v2 (system-explicit step 5) supersedes the old
        // drop-and-strip behavior for REFERENCED sets: geoip-fastly's file
        // is unreplaceable at the official repo, but a route rule still
        // references it, so the definition and the rule are both kept (and
        // reported via keptReferenced in the report path) instead of being
        // dropped. Unreferenced dead sets still drop — see
        // ConfigNormalizeTest.healDropsUnreplaceableOnlyAtOfficialUrls.
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
                                    .put("url", "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@20261012/geoip-cn.srs"),
                            )
                            .put(
                                JSONObject()
                                    .put("tag", "geoip-fastly")
                                    .put("type", "remote")
                                    .put("url", "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@20261012/geoip-fastly.srs"),
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
        assertEquals(2, sets.length())
        assertEquals("geoip-cn", sets.getJSONObject(0).getString("tag"))
        assertEquals("geoip-fastly", sets.getJSONObject(1).getString("tag"))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        val kept = ruleWithRuleSet(rules, "geoip-cn")
        assertEquals("direct", kept.getString("outbound"))
        assertEquals(true, hasRuleSet(rules, "geoip-fastly"))
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
        val hijack = ruleWithAction(rules, "hijack-dns")
        assertEquals(false, hijack.has("outbound"))
        var adsReject = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("domain_suffix") == ".ads") {
                assertEquals("reject", rule.getString("action"))
                adsReject = true
            }
        }
        assertEquals(true, adsReject)
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
                                "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@20261024005255/geosite-cn.srs",
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
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@20261024005255/geosite-cn.srs",
            sets.getJSONObject(1).getString("url"),
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@20261024005255/geosite-category-ads-all.srs",
            sets.getJSONObject(2).getString("url"),
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@20261024005255/geosite-cn.srs",
            sets.getJSONObject(3).getString("url"),
        )
    }

    @Test
    fun nativeSingBoxRouteIsNotRewritten() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "shadowsocks")
                        .put("tag", "n")
                        .put("server", "1.1.1.1")
                        .put("server_port", 443)
                        .put("method", "aes-128-gcm")
                        .put("password", "p"),
                ),
            )
            .put(
                "route",
                JSONObject()
                    .put("final", "n")
                    .put(
                        "rules",
                        JSONArray().put(JSONObject().put("domain", "example.com").put("outbound", "n")),
                    ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("example.com", rules.getJSONObject(0).getString("domain"))
        assertEquals("n", rules.getJSONObject(0).getString("outbound"))
        assertEquals("n", out.getJSONObject("route").getString("final"))
        assertEquals(false, out.toString().contains("hijack-dns"))
        assertEquals(false, out.toString().contains("198.18.0.0/15"))
    }

    private fun ruleWithAction(rules: JSONArray, action: String): JSONObject {
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("action") == action) return rule
        }
        throw AssertionError("missing action $action")
    }

    private fun ruleWithRuleSet(rules: JSONArray, tag: String): JSONObject {
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("rule_set") == tag) return rule
        }
        throw AssertionError("missing rule_set $tag")
    }

    private fun hasRuleSet(rules: JSONArray, tag: String): Boolean {
        for (i in 0 until rules.length()) {
            if (rules.getJSONObject(i).optString("rule_set") == tag) return true
        }
        return false
    }

    @Test
    fun logFileOutputIsDropped() {
        val out = org.json.JSONObject(
            ConfigCompat.sanitize(
                """{"log":{"level":"debug","output":"box.log"},"outbounds":[{"type":"direct","tag":"direct"}]}""",
            ),
        )
        org.junit.Assert.assertFalse(out.getJSONObject("log").has("output"))
        org.junit.Assert.assertEquals("debug", out.getJSONObject("log").getString("level"))
    }

    @Test
    fun sanitizeDetailedSurfacesDroppedNodesAsNotes() {
        // xhttp 节点被删掉时，notes 里必须有具名提示（用户划线：不藏报错）
        val content = """
            {"outbounds":[
              {"type":"direct","tag":"direct"},
              {"type":"trojan","tag":"ok-node","server":"ok.example.com","server_port":443,"password":"pw"},
              {"type":"vless","tag":"xhttp-node","server":"x.example.com","server_port":443,
               "uuid":"11111111-1111-1111-1111-111111111111",
               "transport":{"type":"xhttp","path":"/"}}
            ]}
        """.trimIndent()
        val detailed = ConfigCompat.sanitizeDetailed(content)
        org.junit.Assert.assertTrue(detailed.notes.any { it.contains("xhttp-node") })
        val tags = (0 until org.json.JSONObject(detailed.content).getJSONArray("outbounds").length())
            .map { org.json.JSONObject(detailed.content).getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        org.junit.Assert.assertFalse(tags.contains("xhttp-node"))
        org.junit.Assert.assertTrue(tags.contains("direct"))
        org.junit.Assert.assertTrue(tags.contains("ok-node"))
    }

    @Test
    fun dropUnsupportedLeavesRemovesEmptiedGroupAndRetargetsFinal() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "x1", "transport": {"type": "xhttp"}},
                {"type": "shadowsocks", "tag": "good", "server": "192.0.2.1", "server_port": 8388, "method": "aes-128-gcm", "password": "pw"},
                {"type": "selector", "tag": "G1", "outbounds": ["x1"]},
                {"type": "selector", "tag": "Main", "outbounds": ["good"]}
              ],
              "route": {"final": "G1"}
            }
            """.trimIndent(),
        )
        val dropped = ConfigCompat.dropUnsupportedLeaves(root)
        assertTrue(dropped.contains("x1"))
        val tags = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        // A group whose only member was pruned must not keep a dangling
        // final pointer (the kernel rejects an empty selector).
        assertFalse(tags.contains("G1"))
        assertTrue(tags.contains("Main"))
        assertEquals("Main", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun dropUnsupportedLeavesKeepsEndpointMembers() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "x1", "transport": {"type": "xhttp"}},
                {"type": "selector", "tag": "Main", "outbounds": ["wg1", "x1"]}
              ],
              "endpoints": [
                {"type": "wireguard", "tag": "wg1"}
              ],
              "route": {"final": "Main"}
            }
            """.trimIndent(),
        )
        ConfigCompat.dropUnsupportedLeaves(root)
        val main = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "Main" }
        val members = main.getJSONArray("outbounds")
        // WireGuard nodes live in endpoints; a group member naming one is
        // a valid reference, not a dangling one.
        assertEquals(1, members.length())
        assertEquals("wg1", members.getString(0))
    }

    @Test
    fun sanitizeOutboundDropsHyphenPluginOptsWhenBothKeysPresent() {
        val o = JSONObject()
            .put("type", "shadowsocks")
            .put("plugin", "obfs-local")
            .put("plugin-opts", JSONObject().put("mode", "http"))
            .put("plugin_opts", "obfs=http;obfs-host=example.com")
        ConfigCompat.sanitizeOutbound(o)
        // The kernel rejects unknown outbound fields; the hyphen variant
        // must go even when the underscore variant already exists.
        assertFalse(o.has("plugin-opts"))
        assertEquals("obfs=http;obfs-host=example.com", o.getString("plugin_opts"))
    }

    @Test
    fun stripBrokenDnsDetoursKeepsEndpointDetour() {
        val root = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put(
                "endpoints",
                JSONArray().put(JSONObject().put("type", "wireguard").put("tag", "wg-out")),
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
                            .put("detour", "wg-out"),
                    ),
                ),
            )
        assertFalse(ConfigCompat.stripBrokenDnsDetours(root))
        val server = root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("wg-out", server.getString("detour"))
    }

}
