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
    fun healStringIsSafeOnGarbage() {
        assertEquals("not-json", ConfigNormalize.healString("not-json"))
        assertEquals("", ConfigNormalize.healString(""))
        val healed = ConfigNormalize.heal("{not json")
        assertEquals("{not json", healed.content)
        assertTrue(healed.notes.isEmpty())
    }

    @Test
    fun dropRemoteRuleSetsMatchingRemovesOnlyTheBadSet() {
        val root = JSONObject(
            """
            {
              "outbounds": [{"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443}],
              "route": {
                "rule_set": [
                  {"tag": "geoip-cn", "type": "remote", "url": "https://example.com/geoip-cn.srs"},
                  {"tag": "geosite-cn", "type": "remote", "url": "https://example.com/geosite-cn.srs"}
                ],
                "rules": [
                  {"rule_set": "geoip-cn", "outbound": "direct"},
                  {"rule_set": "geosite-cn", "outbound": "direct"}
                ],
                "final": "n1"
              }
            }
            """.trimIndent(),
        )
        assertTrue(ConfigInboundCompat.dropRemoteRuleSetsMatching(root, listOf("geosite-cn.srs")))
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(1, sets.length())
        assertEquals("geoip-cn", sets.getJSONObject(0).getString("tag"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("geoip-cn", rules.getJSONObject(0).getString("rule_set"))
        assertEquals("n1", root.getJSONObject("route").getString("final"))
        assertEquals("1.2.3.4", root.getJSONArray("outbounds").getJSONObject(0).getString("server"))
    }
}
