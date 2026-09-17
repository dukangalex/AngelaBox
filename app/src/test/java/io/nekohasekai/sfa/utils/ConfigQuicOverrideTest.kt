package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigQuicOverrideTest {

    @Test
    fun logLevelForcedToInfo() {
        val root = JSONObject().put("log", JSONObject().put("level", "debug"))
        ConfigQuicOverride.applyLogLevel(root)
        assertEquals("info", root.getJSONObject("log").getString("level"))
    }

    @Test
    fun logSectionCreatedWhenMissing() {
        val root = JSONObject()
        ConfigQuicOverride.applyLogLevel(root)
        assertEquals("info", root.getJSONObject("log").getString("level"))
    }

    @Test
    fun dnsProtectOverwritesExisting() {
        val root = JSONObject()
            .put("dns", JSONObject().put("independent_cache", false))
            .put("route", JSONObject().put("auto_detect_interface", false))
        ConfigQuicOverride.applyDnsProtect(root)
        assertTrue(root.getJSONObject("dns").getBoolean("independent_cache"))
        assertTrue(root.getJSONObject("route").getBoolean("auto_detect_interface"))
    }

    @Test
    fun strictRouteForcesTunEvenIfFalse() {
        val root = JSONObject().put(
            "inbounds",
            JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun-in").put("strict_route", false)),
        )
        ConfigQuicOverride.applyStrictRoute(root)
        assertTrue(root.getJSONArray("inbounds").getJSONObject(0).getBoolean("strict_route"))
    }

    @Test
    fun disableIpv6OverwritesStrategy() {
        val root = JSONObject().put("dns", JSONObject().put("strategy", "prefer_ipv6"))
        ConfigQuicOverride.applyDisableIpv6(root)
        assertEquals("ipv4_only", root.getJSONObject("dns").getString("strategy"))
        val rule = root.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals(6, rule.getInt("ip_version"))
        assertEquals("reject", rule.getString("action"))
    }

    @Test
    fun onDemandWritesMatchingEndpointsAndOutbounds() {
        val root = JSONObject()
            .put(
                "endpoints",
                JSONArray()
                    .put(JSONObject().put("type", "wireguard").put("tag", "wg"))
                    .put(JSONObject().put("type", "tailscale").put("tag", "ts"))
                    .put(JSONObject().put("type", "http").put("tag", "skip-ep")),
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "openvpn").put("tag", "ov"))
                    .put(JSONObject().put("type", "openconnect").put("tag", "oc"))
                    .put(JSONObject().put("type", "shadowsocks").put("tag", "ss")),
            )
        ConfigQuicOverride.applyOnDemand(root, true)
        val endpoints = root.getJSONArray("endpoints")
        assertTrue(endpoints.getJSONObject(0).getBoolean("on_demand"))
        assertTrue(endpoints.getJSONObject(1).getBoolean("on_demand"))
        assertTrue(!endpoints.getJSONObject(2).has("on_demand"))
        val outbounds = root.getJSONArray("outbounds")
        assertTrue(outbounds.getJSONObject(0).getBoolean("on_demand"))
        assertTrue(outbounds.getJSONObject(1).getBoolean("on_demand"))
        assertTrue(!outbounds.getJSONObject(2).has("on_demand"))
    }

    @Test
    fun onDemandFalseOverwritesExistingTrue() {
        val root = JSONObject().put(
            "endpoints",
            JSONArray().put(JSONObject().put("type", "wireguard").put("on_demand", true)),
        )
        ConfigQuicOverride.applyOnDemand(root, false)
        assertTrue(!root.getJSONArray("endpoints").getJSONObject(0).getBoolean("on_demand"))
    }

    @Test
    fun onDemandNoMatchingTypesIsNoOp() {
        val root = JSONObject().put(
            "outbounds",
            JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")),
        )
        ConfigQuicOverride.applyOnDemand(root, true)
        assertTrue(!root.getJSONArray("outbounds").getJSONObject(0).has("on_demand"))
    }
}

