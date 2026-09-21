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
    fun applyNormalizesDashboardDownloadAndBindsManagementToLoopback() {
        val root = JSONObject(
            """
            {
              "experimental": {
                "clash_api": {
                  "external_controller": "0.0.0.0:9090",
                  "external_ui_download_url": "http://example.com/dashboard.zip"
                }
              }
            }
            """.trimIndent(),
        )

        val notes = ConfigNormalize.apply(root)

        assertTrue(notes.any { it.contains("面板下载") })
        assertTrue(notes.any { it.contains("本机访问") })
        val clash = root.getJSONObject("experimental").getJSONObject("clash_api")
        assertEquals("127.0.0.1:9090", clash.getString("external_controller"))
        assertFalse(clash.has("external_ui_download_url"))
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
    fun healReturnsSilentCompatibilityMigrations() {
        val healed = ConfigNormalize.heal(
            """{"inbounds":[{"type":"tun","stack":"system"}]}""",
        )

        assertTrue(healed.changed.not())
        assertFalse(JSONObject(healed.content).getJSONArray("inbounds").getJSONObject(0).has("stack"))
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
}
