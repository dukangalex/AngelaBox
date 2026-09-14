package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigChinaDirectTest {

    @Test
    fun packsLanChinaDnsAndDomainRules() {
        val rules = ConfigChinaDirect.chinaRouteRules("direct", emptySet())
        val text = rules.toString()
        assertTrue(text.contains("ip_is_private"))
        assertTrue(text.contains("114.114.114.114/32"))
        assertTrue(text.contains("2400:3200::1/128"))
        assertTrue(text.contains("dns.alidns.com"))
        assertTrue(text.contains("qq.com"))
        assertTrue(text.contains("local"))
        assertFalse(text.contains("rule_set"))
    }

    @Test
    fun reusesExistingGeoipRuleSet() {
        val rules = ConfigChinaDirect.chinaRouteRules("direct", setOf("geoip-cn", "geosite-cn"))
        val text = rules.toString()
        assertTrue(text.contains("geoip-cn"))
        assertTrue(text.contains("geosite-cn"))
    }

    @Test
    fun createsDirectOutboundWhenMissing() {
        val root = JSONObject().put(
            "outbounds",
            JSONArray().put(JSONObject().put("type", "vless").put("tag", "node")),
        )
        ConfigChinaDirect.apply(root)
        val outs = root.getJSONArray("outbounds")
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }
        assertTrue(tags.contains("direct"))
        val routeRules = root.getJSONObject("route").getJSONArray("rules")
        assertTrue(routeRules.length() >= 5)
        assertFalse(root.optJSONObject("dns")?.optJSONArray("servers")?.toString()
            ?.contains(ConfigChinaDirect.CN_DNS_TAG) == true)
    }

    @Test
    fun forceAppliesEvenWhenDnsAndRouteMissing() {
        val root = JSONObject()
        ConfigChinaDirect.apply(root)
        assertTrue(root.has("route"))
        assertTrue(root.has("outbounds"))
        val first = root.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertTrue(first.optBoolean("ip_is_private"))
        assertEquals("direct", first.getString("outbound"))
        assertFalse(root.has("dns"))
    }

    @Test
    fun doesNotInjectChinaDnsServer() {
        val root = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("type", "udp").put("tag", "remote").put("server", "8.8.8.8"),
                    ),
                ),
            )
        ConfigChinaDirect.apply(root)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        assertEquals(1, servers.length())
        assertEquals("remote", servers.getJSONObject(0).getString("tag"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val text = rules.toString()
        assertTrue(text.contains("223.5.5.5/32"))
        assertTrue(text.contains("ip_is_private"))
    }

    @Test
    fun dropsLegacyInjectedChinaDnsAndEmptyDirectDetour() {
        val root = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "udp")
                                .put("tag", ConfigChinaDirect.CN_DNS_TAG)
                                .put("server", "223.5.5.5")
                                .put("detour", "direct"),
                        ).put(
                            JSONObject()
                                .put("type", "https")
                                .put("tag", "alidns")
                                .put("server", "223.5.5.5")
                                .put("detour", "direct"),
                        ),
                    )
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("domain_suffix", JSONArray().put("cn"))
                                .put("server", ConfigChinaDirect.CN_DNS_TAG),
                        ),
                    ),
            )
        ConfigChinaDirect.apply(root)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        assertEquals(1, servers.length())
        val alidns = servers.getJSONObject(0)
        assertEquals("alidns", alidns.getString("tag"))
        assertFalse(alidns.has("detour"))
        val dnsRules = root.getJSONObject("dns").optJSONArray("rules") ?: JSONArray()
        for (i in 0 until dnsRules.length()) {
            assertFalse(dnsRules.getJSONObject(i).optString("server") == ConfigChinaDirect.CN_DNS_TAG)
        }
    }

    @Test
    fun usesFallbackTagWhenDirectIsTakenBySelector() {
        val outs = JSONArray().put(
            JSONObject().put("type", "selector").put("tag", "direct")
                .put("outbounds", JSONArray().put("node")),
        )
        val tag = ConfigChinaDirect.findOrCreateDirect(outs)
        assertEquals(ConfigChinaDirect.DIRECT_FALLBACK_TAG, tag)
        val created = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("tag") == tag }
        assertEquals("direct", created.getString("type"))
    }
}
