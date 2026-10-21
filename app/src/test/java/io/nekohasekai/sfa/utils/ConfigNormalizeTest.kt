package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNormalizeTest {

    @Test
    fun webrtcRejectsStunPortsAndHostnames() {
        val rules = ConfigNormalize.webrtcRejectRules()
        assertEquals(3, rules.length())
        val udp = rules.getJSONObject(0)
        assertEquals("udp", udp.getString("network"))
        assertEquals("reject", udp.getString("action"))
        val udpPorts = (0 until udp.getJSONArray("port").length())
            .map { udp.getJSONArray("port").getInt(it) }
            .toSet()
        assertTrue(udpPorts.containsAll(setOf(3478, 19302, 5349)))
        val tcp = rules.getJSONObject(1)
        assertEquals("tcp", tcp.getString("network"))
        assertEquals("reject", tcp.getString("action"))
        val keywords = rules.getJSONObject(2).getJSONArray("domain_keyword")
        val keys = (0 until keywords.length()).map { keywords.getString(it) }.toSet()
        assertTrue(keys.contains("stun."))
        assertTrue(keys.contains("turn."))
    }

    @Test
    fun cnDomainListCoversCommonSuffixes() {
        val arr = ConfigNormalize.cnDomainSuffixArray()
        val values = (0 until arr.length()).map { arr.getString(it) }.toSet()
        assertTrue(values.contains("cn"))
        assertTrue(values.contains("qq.com"))
        assertTrue(values.contains("bilibili.com"))
        assertEquals(ConfigNormalize.CN_DOMAIN_SUFFIXES.size, arr.length())
    }

    @Test
    fun applyMigratesLegacyFakeipAndKeepsNodes() {
        val root = JSONObject(
            """
            {
              "dns": {"fakeip": {"enabled": true, "inet4_range": "198.18.0.0/15"}},
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443},
                {"type": "selector", "tag": "proxy", "outbounds": ["n1"]}
              ],
              "route": {"rules": [{"domain": "example.com", "outbound": "proxy"}], "final": "proxy"}
            }
            """.trimIndent(),
        )
        val notes = ConfigNormalize.apply(root)
        assertTrue(notes.isNotEmpty())
        assertFalse(root.getJSONObject("dns").has("fakeip"))
        val node = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("1.2.3.4", node.getString("server"))
        assertEquals(443, node.getInt("server_port"))
        assertEquals("n1", node.getString("tag"))
        val group = root.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("selector", group.getString("type"))
        assertEquals("n1", group.getJSONArray("outbounds").getString(0))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val userRule = (0 until rules.length()).map { rules.getJSONObject(it) }
            .first { it.optString("domain") == "example.com" }
        assertEquals("proxy", userRule.getString("outbound"))
        assertEquals("proxy", root.getJSONObject("route").getString("final"))
        val again = ConfigNormalize.apply(root)
        assertTrue(again.isEmpty())
    }

    @Test
    fun applyDoesNotInjectChinaDirectOrAds() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443}
              ],
              "route": {"final": "n1"}
            }
            """.trimIndent(),
        )
        ConfigNormalize.apply(root)
        val text = root.toString()
        assertFalse(text.contains("geoip-cn"))
        assertFalse(text.contains("geosite-category-ads"))
        assertEquals("n1", root.getJSONObject("route").getString("final"))
        assertEquals("1.2.3.4", root.getJSONArray("outbounds").getJSONObject(0).getString("server"))
    }

    @Test
    fun ensureClashModesInjectsGlobalAndDirect() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443},
                {"type": "selector", "tag": "proxy", "outbounds": ["n1"]},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {
                "rules": [
                  {"protocol": "dns", "action": "hijack-dns"},
                  {"domain": "example.com", "outbound": "proxy"}
                ],
                "final": "proxy"
              }
            }
            """.trimIndent(),
        )
        assertTrue(ConfigNormalize.ensureClashModes(root))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val modes = (0 until rules.length()).map { rules.getJSONObject(it) }
        assertEquals("hijack-dns", modes[0].optString("action"))
        val global = modes.first { it.optString("clash_mode") == "Global" }
        val direct = modes.first { it.optString("clash_mode") == "Direct" }
        assertEquals("proxy", global.getString("outbound"))
        assertEquals("direct", direct.getString("outbound"))
        assertFalse(ConfigNormalize.ensureClashModes(root))
        assertEquals("example.com", modes.last().optString("domain"))
    }

    @Test
    fun ensureClashModesPrefersSelectorOverCatchAllFinal() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443},
                {"type": "urltest", "tag": "🛡️ 故障转移", "outbounds": ["n1"]},
                {"type": "selector", "tag": "🔰 节点选择", "outbounds": ["n1"]},
                {"type": "selector", "tag": "🐟 漏网之鱼", "outbounds": ["🔰 节点选择", "n1"]},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "🐟 漏网之鱼", "rules": []}
            }
            """.trimIndent(),
        )
        assertTrue(ConfigNormalize.ensureClashModes(root))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val global = (0 until rules.length()).map { rules.getJSONObject(it) }
            .first { it.optString("clash_mode") == "Global" }
        assertEquals("🔰 节点选择", global.getString("outbound"))
    }

    @Test
    fun applyDoesNotInjectClashModes() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "n1"}
            }
            """.trimIndent(),
        )
        ConfigNormalize.apply(root)
        assertFalse(root.toString().contains("clash_mode"))
    }

    @Test
    fun applyMigratesDnsOutboundAndKeepsUserNodes() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "dns", "tag": "dns-out"},
                {"type": "vless", "tag": "n1", "server": "8.8.8.8", "server_port": 443},
                {"type": "selector", "tag": "proxy", "outbounds": ["n1", "dns-out"]}
              ],
              "route": {
                "rules": [{"protocol": "dns", "outbound": "dns-out"}],
                "final": "proxy"
              }
            }
            """.trimIndent(),
        )
        val notes = ConfigNormalize.apply(root)
        assertTrue(notes.any { it.contains("dns/block") })
        val tags = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertFalse(tags.contains("dns-out"))
        assertTrue(tags.contains("n1"))
        assertTrue(tags.contains("proxy"))
        val group = root.getJSONArray("outbounds").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("tag") == "proxy" }
        }
        val members = (0 until group.getJSONArray("outbounds").length())
            .map { group.getJSONArray("outbounds").getString(it) }
        assertEquals(listOf("n1"), members)
        assertEquals("proxy", root.getJSONObject("route").getString("final"))
        assertEquals("8.8.8.8", root.getJSONArray("outbounds").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("tag") == "n1" }
        }.getString("server"))
    }

    @Test
    fun healStringIsSafeOnGarbage() {
        assertEquals("not-json", ConfigNormalize.healString("not-json"))
        assertEquals("", ConfigNormalize.healString(""))
        val healed = ConfigNormalize.heal("{not json")
        assertEquals("{not json", healed.content)
        assertTrue(healed.notes.isEmpty())
    }

    @Test
    fun dropRemoteRuleSetsMatchingIgnoresDigitAndSubstringNeedles() {
        val root = JSONObject(
            """
            {
              "route": {
                "rule_set": [
                  {"tag": "geosite-cn", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs"},
                  {"tag": "geosite-google", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-google.srs"}
                ]
              }
            }
            """.trimIndent(),
        )
        assertFalse(ConfigInboundCompat.dropRemoteRuleSetsMatching(root, listOf("1", "3", "github")))
        assertEquals(2, root.getJSONObject("route").getJSONArray("rule_set").length())
    }

    @Test
    fun replaceRemoteRuleSetsKeepsTagAndOfficialUrl() {
        val root = JSONObject(
            """
            {
              "outbounds": [{"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443}],
              "route": {
                "rule_set": [
                  {"tag": "telegram", "type": "remote", "url": "https://example.com/telegram.srs"},
                  {"tag": "geoip-cn", "type": "remote", "url": "https://example.com/geoip-cn.srs"}
                ],
                "rules": [
                  {"rule_set": "telegram", "outbound": "n1"},
                  {"rule_set": "geoip-cn", "outbound": "direct"}
                ],
                "final": "n1"
              }
            }
            """.trimIndent(),
        )
        assertTrue(
            ConfigInboundCompat.replaceRemoteRuleSetsMatching(
                root,
                listOf("telegram.srs", "github.srs", "3", "google", "1"),
            ),
        )
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(2, sets.length())
        val telegram = (0 until sets.length()).map { sets.getJSONObject(it) }.first { it.getString("tag") == "telegram" }
        assertTrue(telegram.getString("url").contains("SagerNet/sing-geosite@rule-set/geosite-telegram.srs"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals("telegram", rules.getJSONObject(0).getString("rule_set"))
        assertEquals("n1", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun healReplacesShortAndMissingGeoipWithGeosite() {
        val root = JSONObject(
            """
            {
              "route": {
                "rule_set": [
                  {"tag": "telegram", "type": "remote", "url": "https://raw.githubusercontent.com/foo/bar/main/telegram.srs"},
                  {"tag": "geoip-telegram", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-telegram.srs"},
                  {"tag": "geoip-fastly", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-fastly.srs"},
                  {"tag": "geoip-cn", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"}
                ],
                "rules": [
                  {"rule_set": "telegram", "outbound": "proxy"},
                  {"rule_set": "geoip-telegram", "outbound": "proxy"},
                  {"rule_set": "geoip-fastly", "outbound": "direct"},
                  {"rule_set": "geoip-cn", "outbound": "direct"}
                ]
              }
            }
            """.trimIndent(),
        )
        ConfigInboundCompat.rewriteRuleSetUrls(root)
        assertTrue(ConfigInboundCompat.healRemoteRuleSets(root))
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        val byTag = (0 until sets.length()).associate {
            val o = sets.getJSONObject(it)
            o.getString("tag") to o.getString("url")
        }
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-telegram.srs",
            byTag["telegram"],
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-telegram.srs",
            byTag["geoip-telegram"],
        )
        assertFalse(byTag.containsKey("geoip-fastly"))
        assertTrue(byTag["geoip-cn"]!!.contains("geoip-cn.srs"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val refs = (0 until rules.length()).map { rules.getJSONObject(it).optString("rule_set") }
        assertTrue(refs.contains("telegram"))
        assertTrue(refs.contains("geoip-telegram"))
        assertFalse(refs.contains("geoip-fastly"))
        assertTrue(refs.contains("geoip-cn"))
    }

    @Test
    fun healRemoteRuleSetsReportsSwappedAndDroppedNames() {
        val root = JSONObject(
            """
            {
              "route": {
                "rule_set": [
                  {"tag": "telegram", "type": "remote", "url": "https://raw.githubusercontent.com/foo/bar/main/telegram.srs"},
                  {"tag": "geoip-fastly", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-fastly.srs"},
                  {"tag": "geoip-cn", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"}
                ],
                "rules": []
              }
            }
            """.trimIndent(),
        )
        val heal = ConfigInboundCompat.healRemoteRuleSetsWithReport(root)
        assertTrue(heal.changed)
        // Swapping a provider-hosted set for the official one changes
        // routing semantics; the report must name exactly what happened.
        assertTrue(heal.swapped.contains("telegram"))
        assertTrue(heal.dropped.contains("geoip-fastly"))
    }

    @Test
    fun healRemoteRuleSetsKeepsNonObjectEntries() {
        val root = JSONObject(
            """
            {
              "route": {
                "rule_set": [
                  "not-an-object",
                  {"tag": "geoip-cn", "type": "remote", "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"}
                ],
                "rules": []
              }
            }
            """.trimIndent(),
        )
        val heal = ConfigInboundCompat.healRemoteRuleSetsWithReport(root)
        // Nothing to heal: the report must say so, and the array must come
        // through untouched — non-object entries included, never silently
        // dropped, and the boolean must not claim a change that did not
        // happen.
        assertFalse(heal.changed)
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(2, sets.length())
        assertEquals("not-an-object", sets.getString(0))
    }

    @Test
    fun healMissingOutboundRefsPrefersRouteFinalGroup() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443},
                {"type": "selector", "tag": "HK", "outbounds": ["n1"]},
                {"type": "selector", "tag": "Main", "outbounds": ["n1"]}
              ],
              "route": {
                "final": "Main",
                "rules": [{"domain": ["x.example.com"], "outbound": "gone"}]
              }
            }
            """.trimIndent(),
        )
        val heal = ConfigInboundCompat.healMissingOutboundRefsWithReport(root)
        assertTrue(heal.changed)
        // A dangling rule must fall back to the profile's main group (the
        // route.final target), not to whatever group happens to be first —
        // silently re-routing a rule to a region group inverts intent.
        assertTrue(heal.retargets.contains("gone → Main"))
        assertEquals(
            "Main",
            root.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound"),
        )
    }

    @Test
    fun ensureClashModesKeepsTerminalDnsRuleBehindUserRules() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443},
                {"type": "selector", "tag": "proxy", "outbounds": ["n1"]},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {
                "rules": [
                  {"domain": ["a.example.com"], "outbound": "proxy"},
                  {"protocol": "dns", "outbound": "proxy"},
                  {"protocol": "dns", "action": "hijack-dns"}
                ],
                "final": "proxy"
              }
            }
            """.trimIndent(),
        )
        assertTrue(ConfigNormalize.ensureClashModes(root))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val list = (0 until rules.length()).map { rules.getJSONObject(it) }
        // hijack-dns is infrastructure and stays in front…
        assertEquals("hijack-dns", list[0].getString("action"))
        // …but the terminal protocol:dns routing rule must not jump ahead
        // of the user rule that originally preceded it (first-match order).
        val domainIdx = list.indexOfFirst { it.optJSONArray("domain")?.optString(0) == "a.example.com" }
        val dnsIdx = list.indexOfFirst { it.optString("protocol") == "dns" && it.has("outbound") }
        assertTrue(domainIdx in 0 until dnsIdx)
    }

    private fun ruleSetRoot(vararg sets: String): JSONObject =
        JSONObject("""{"route":{"rule_set":[${sets.joinToString(",")}]}}""")

    @Test
    fun substituteCachedRuleSetsSwapsRemoteWithCache() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rs-sub-${System.nanoTime()}")
        val cacheDir = java.io.File(dir, "rule-sets").apply { mkdirs() }
        java.io.File(cacheDir, "geoip-cn.srs").writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(cacheDir, "geosite-cn.json").writeText("""{"rules":[]}""")
        val root = ruleSetRoot(
            """{"type":"remote","tag":"geoip-cn","format":"binary","url":"https://example.com/geoip-cn.srs","download_detour":"proxy","update_interval":"1d"}""",
            """{"type":"remote","tag":"geosite-cn","format":"source","url":"https://example.com/geosite-cn.json"}""",
            """{"type":"remote","tag":"geoip-us","format":"binary","url":"https://example.com/geoip-us.srs"}""",
            """{"type":"local","tag":"mine","format":"binary","path":"/data/mine.srs"}""",
        )
        val done = ConfigNormalize.substituteCachedRuleSets(root, dir)
        assertEquals(listOf("geoip-cn", "geosite-cn"), done)
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        val cn = sets.getJSONObject(0)
        assertEquals("local", cn.getString("type"))
        assertEquals("binary", cn.getString("format"))
        assertTrue(cn.getString("path").endsWith("rule-sets/geoip-cn.srs"))
        assertFalse(cn.has("url"))
        assertFalse(cn.has("download_detour"))
        assertFalse(cn.has("update_interval"))
        val site = sets.getJSONObject(1)
        assertEquals("local", site.getString("type"))
        assertEquals("source", site.getString("format"))
        // No cache for geoip-us: stays remote for the caller's next rung.
        assertEquals("remote", sets.getJSONObject(2).getString("type"))
        // Already-local sets are untouched.
        assertEquals("/data/mine.srs", sets.getJSONObject(3).getString("path"))
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------
    // ConfigQuarantine: check-driven isolation + floor (AB-010).
    // The fake oracle rejects any config still carrying an "x-bad"
    // marker key — the stand-in for a field the real kernel refuses.
    // ---------------------------------------------------------------

    private fun markerOracle(): ConfigCheckOracle = ConfigCheckOracle { json ->
        if (json.contains("\"x-bad\"")) "decode config: unknown field x-bad" else null
    }

    private fun tagsOf(arr: org.json.JSONArray): List<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("tag") }

    @Test
    fun quarantineIsolatesBadDnsRuleAndNodeOnly() {
        val content = """
            {
              "dns": {"servers": [
                {"type": "local", "tag": "local"},
                {"type": "tcp", "tag": "bad-dns", "server": "1.2.3.4", "x-bad": true},
                {"type": "tcp", "tag": "good-dns", "server": "8.8.8.8"}
              ], "final": "bad-dns"},
              "inbounds": [{"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"]}],
              "outbounds": [
                {"type": "vless", "tag": "good-1", "server": "a.example.com", "server_port": 443},
                {"type": "vless", "tag": "bad-1", "server": "b.example.com", "server_port": 443, "x-bad": true},
                {"type": "selector", "tag": "proxy", "outbounds": ["good-1", "bad-1"]},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"rules": [
                {"domain": "ok.example.com", "outbound": "proxy"},
                {"domain": "bad.example.com", "outbound": "proxy", "x-bad": true}
              ], "final": "proxy"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, markerOracle())
        assertTrue(outcome != null)
        outcome!!
        assertFalse(outcome.floorUsed)
        assertTrue(outcome.removedItems.any { it.contains("bad-dns") })
        assertTrue(outcome.removedItems.any { it.contains("bad-1") })
        assertTrue(outcome.removedItems.any { it.contains("分流规则") })
        val root = JSONObject(outcome.content)
        val dns = root.getJSONObject("dns")
        assertEquals(listOf("local", "good-dns"), tagsOf(dns.getJSONArray("servers")))
        // final pointed at the removed server: retargeted at a survivor.
        assertEquals("local", dns.getString("final"))
        val outs = root.getJSONArray("outbounds")
        assertEquals(listOf("good-1", "proxy", "direct"), tagsOf(outs))
        val members = outs.getJSONObject(1).getJSONArray("outbounds")
        assertEquals(1, members.length())
        assertEquals("good-1", members.getString(0))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("ok.example.com", rules.getJSONObject(0).getString("domain"))
        assertEquals("proxy", root.getJSONObject("route").getString("final"))
        // The surviving node is field-for-field intact.
        val good = outs.getJSONObject(0)
        assertEquals("a.example.com", good.getString("server"))
        assertEquals(443, good.getInt("server_port"))
    }

    @Test
    fun quarantineLeavesAcceptedConfigAlone() {
        val content = """
            {
              "dns": {"servers": [{"type": "local", "tag": "local"}], "final": "local"},
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "a.example.com", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "n1"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, markerOracle())
        assertTrue(outcome != null)
        outcome!!
        assertFalse(outcome.floorUsed)
        assertTrue(outcome.removedItems.isEmpty())
        val outs = JSONObject(outcome.content).getJSONArray("outbounds")
        assertTrue(tagsOf(outs).contains("n1"))
    }

    @Test
    fun quarantineRemovesBadLocalRuleSetAndItsRulesOnly() {
        val content = """
            {
              "dns": {"servers": [{"type": "local", "tag": "local"}], "final": "local"},
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "a.example.com", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"rule_set": [
                {"type": "local", "tag": "bad-set", "format": "source", "path": "/nonexistent.srs", "x-bad": true},
                {"type": "remote", "tag": "good-set", "format": "binary", "url": "https://example.com/good.srs"}
              ], "rules": [
                {"rule_set": "bad-set", "outbound": "n1"},
                {"rule_set": ["good-set"], "outbound": "n1"},
                {"domain": "x.example.com", "outbound": "n1"}
              ], "final": "n1"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, markerOracle())
        assertTrue(outcome != null)
        outcome!!
        assertFalse(outcome.floorUsed)
        assertTrue(outcome.removedItems.any { it.contains("bad-set") })
        val route = JSONObject(outcome.content).getJSONObject("route")
        assertEquals(listOf("good-set"), tagsOf(route.getJSONArray("rule_set")))
        // Only the rule that referenced the bad set alone is gone.
        assertEquals(2, route.getJSONArray("rules").length())
    }

    @Test
    fun quarantineFloorWhenFaultOutsideCandidates() {
        val content = """
            {
              "experimental": {"x-bad": true},
              "dns": {"servers": [{"type": "udp", "tag": "dns1", "server": "9.9.9.9"}], "final": "dns1"},
              "outbounds": [
                {"type": "trojan", "tag": "t1", "server": "a.example.com", "server_port": 443, "password": "p"},
                {"type": "vless", "tag": "v1", "server": "b.example.com", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"rules": [{"domain": "a.com", "outbound": "t1"}], "final": "t1"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, markerOracle())
        assertTrue(outcome != null)
        outcome!!
        assertTrue(outcome.floorUsed)
        val root = JSONObject(outcome.content)
        assertFalse(root.has("experimental"))
        val outs = root.getJSONArray("outbounds")
        assertTrue(tagsOf(outs).containsAll(listOf("t1", "v1", "节点选择", "自动选择")))
        assertEquals("节点选择", root.getJSONObject("route").getString("final"))
        assertFalse(root.getJSONObject("route").has("rules"))
        val dns = root.getJSONObject("dns")
        assertEquals(listOf("dns1"), tagsOf(dns.getJSONArray("servers")))
        assertEquals("dns1", dns.getString("final"))
        val selector = outs.getJSONObject(tagsOf(outs).indexOf("节点选择"))
        assertEquals("自动选择", selector.getString("default"))
    }

    @Test
    fun quarantineFloorDropsBadLeafAndItsDetour() {
        // Oracle rejects the marker AND any mention of the bad leaf:
        // isolation inside the floor must remove the leaf and the
        // surviving node's detour to it — never retarget it at direct.
        val oracle = ConfigCheckOracle { json ->
            if (json.contains("\"x-bad\"") || json.contains("\"bad-b\"")) "rejected" else null
        }
        val content = """
            {
              "experimental": {"x-bad": true},
              "dns": {"servers": [{"type": "local", "tag": "local"}], "final": "local"},
              "outbounds": [
                {"type": "vless", "tag": "good-a", "server": "a.example.com", "server_port": 443, "detour": "bad-b"},
                {"type": "trojan", "tag": "bad-b", "server": "b.example.com", "server_port": 443, "password": "p"},
                {"type": "vless", "tag": "good-c", "server": "c.example.com", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "good-a"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, oracle)
        assertTrue(outcome != null)
        outcome!!
        assertTrue(outcome.floorUsed)
        assertTrue(outcome.removedItems.any { it.contains("bad-b") })
        val outs = JSONObject(outcome.content).getJSONArray("outbounds")
        assertFalse(tagsOf(outs).contains("bad-b"))
        val a = outs.getJSONObject(tagsOf(outs).indexOf("good-a"))
        assertFalse(a.has("detour"))
    }

    @Test
    fun quarantineFloorKeepsSurvivingDetour() {
        val content = """
            {
              "experimental": {"x-bad": true},
              "dns": {"servers": [{"type": "local", "tag": "local"}], "final": "local"},
              "outbounds": [
                {"type": "vless", "tag": "good-a", "server": "a.example.com", "server_port": 443, "detour": "good-b"},
                {"type": "trojan", "tag": "good-b", "server": "b.example.com", "server_port": 443, "password": "p"},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "good-a"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, markerOracle())
        assertTrue(outcome != null)
        outcome!!
        assertTrue(outcome.floorUsed)
        val outs = JSONObject(outcome.content).getJSONArray("outbounds")
        val a = outs.getJSONObject(tagsOf(outs).indexOf("good-a"))
        assertEquals("good-b", a.getString("detour"))
    }

    @Test
    fun quarantineNeverReturnsUncheckedContent() {
        // A starved budget must end in "no result", never in content
        // the oracle has not accepted.
        val content = """
            {
              "dns": {"servers": [{"type": "local", "tag": "local"}], "final": "local"},
              "outbounds": [
                {"type": "vless", "tag": "bad-1", "server": "b.example.com", "server_port": 443, "x-bad": true},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "bad-1"}
            }
        """.trimIndent()
        val starved = ConfigQuarantine.degrade(
            content,
            markerOracle(),
            QuarantineBudget(maxCheckCalls = 1),
        )
        assertTrue(starved == null)
    }

    @Test
    fun quarantineIsolatesOneBadNodeAmongMany() {
        val nodes = (1..10).joinToString(",") { i ->
            val bad = if (i == 7) ", \"x-bad\": true" else ""
            """{"type": "vless", "tag": "n$i", "server": "s$i.example.com", "server_port": 443$bad}"""
        }
        val rules = (1..30).joinToString(",") { i ->
            """{"domain": "d$i.example.com", "outbound": "n1"}"""
        }
        val content = """
            {
              "dns": {"servers": [{"type": "local", "tag": "local"}], "final": "local"},
              "outbounds": [$nodes, {"type": "direct", "tag": "direct"}],
              "route": {"rules": [$rules], "final": "n1"}
            }
        """.trimIndent()
        val outcome = ConfigQuarantine.degrade(content, markerOracle())
        assertTrue(outcome != null)
        outcome!!
        assertFalse(outcome.floorUsed)
        assertEquals(1, outcome.removedItems.count { it.contains("节点") })
        assertTrue(outcome.removedItems.any { it.contains("n7") })
        val root = JSONObject(outcome.content)
        assertEquals(30, root.getJSONObject("route").getJSONArray("rules").length())
        assertEquals(10, root.getJSONArray("outbounds").length())
    }

    // ---------------------------------------------------------------
    // ConfigGate: storage gate aligned with the startup heal (G4).
    // ---------------------------------------------------------------

    @Test
    fun gateAcceptsContentOnlyHealCanFix() {
        // Legacy DNS server "address" form: the plain check rejects
        // it, the startup heal migrates it — the gate must accept.
        val content = """
            {
              "dns": {"servers": [{"tag": "a", "address": "https://dns.example.com/dns-query"}], "final": "a"},
              "outbounds": [
                {"type": "vless", "tag": "n1", "server": "a.example.com", "server_port": 443}
              ],
              "route": {"final": "n1"}
            }
        """.trimIndent()
        val check: (String) -> String? = { candidate ->
            if (candidate.contains("\"address\"")) "unknown field address" else null
        }
        val result = ConfigGate.validate(content, check)
        assertTrue(result is ConfigGate.Result.Accepted)
        assertTrue((result as ConfigGate.Result.Accepted).healRescued)
    }

    @Test
    fun gateAcceptsCleanContentWithoutHeal() {
        val check: (String) -> String? = { null }
        val result = ConfigGate.validate("{\"outbounds\": []}", check)
        assertTrue(result is ConfigGate.Result.Accepted)
        assertFalse((result as ConfigGate.Result.Accepted).healRescued)
    }

    @Test
    fun gateRejectsWithReadableReasonAndStripsUrls() {
        val check: (String) -> String? = {
            "decode config: unknown field at https://secret.example.com/token123 end"
        }
        val result = ConfigGate.validate("{\"outbounds\": []}", check)
        assertTrue(result is ConfigGate.Result.Rejected)
        val reason = (result as ConfigGate.Result.Rejected).reason
        assertTrue(reason.contains("内核原话"))
        assertTrue(reason.contains("没有改成直连"))
        assertFalse(reason.contains("secret.example.com"))
        assertFalse(reason.contains("token123"))
    }

    @Test
    fun substituteCachedRuleSetsIgnoresEmptyCacheFiles() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "rs-sub-${System.nanoTime()}")
        val cacheDir = java.io.File(dir, "rule-sets").apply { mkdirs() }
        java.io.File(cacheDir, "geoip-cn.srs").writeBytes(ByteArray(0))
        val root = ruleSetRoot(
            """{"type":"remote","tag":"geoip-cn","format":"binary","url":"https://example.com/geoip-cn.srs"}""",
        )
        assertTrue(ConfigNormalize.substituteCachedRuleSets(root, dir).isEmpty())
        assertEquals(
            "remote",
            root.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("type"),
        )
        dir.deleteRecursively()
    }

}
