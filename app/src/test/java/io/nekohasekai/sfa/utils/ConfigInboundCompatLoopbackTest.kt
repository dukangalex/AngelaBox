package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigInboundCompatLoopbackTest {
    @Test
    fun sniffFollowsHijackAndIsNotDuplicated() {
        val root = JSONObject().put(
            "route",
            JSONObject().put(
                "rules",
                JSONArray()
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    .put(JSONObject().put("domain_suffix", "example.com").put("outbound", "proxy")),
            ),
        )
        assertTrue(ConfigInboundCompat.ensureSniff(root))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals("hijack-dns", rules.getJSONObject(0).getString("action"))
        assertEquals("sniff", rules.getJSONObject(1).getString("action"))
        assertFalse(ConfigInboundCompat.ensureSniff(root))
    }

    @Test
    fun tunStackStrippedSilently() {
        val root = JSONObject().put(
            "inbounds",
            org.json.JSONArray().put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("stack", "mixed")
                    .put("address", "172.19.0.1/30"),
            ).put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "mixed-in")
                    .put("stack", "system"),
            ),
        )
        assertTrue(ConfigInboundCompat.stripDeprecatedTunStack(root))
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        assertFalse(tun.has("stack"))
        assertEquals("172.19.0.1/30", tun.getString("address"))
        val mixed = root.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("system", mixed.getString("stack"))
        assertFalse(ConfigInboundCompat.stripDeprecatedTunStack(root))
    }

    @Test
    fun clashApiWildcardReboundToLoopback() {
        val root = JSONObject().put(
            "experimental",
            JSONObject().put(
                "clash_api",
                JSONObject().put("external_controller", "0.0.0.0:9090"),
            ),
        )
        assertTrue(ConfigInboundCompat.bindLoopbackOnly(root))
        val listen = root.getJSONObject("experimental")
            .getJSONObject("clash_api")
            .getString("external_controller")
        assertEquals("127.0.0.1:9090", listen)
    }

    @Test
    fun ipv6AnyRebound() {
        assertEquals("127.0.0.1:9090", ConfigInboundCompat.rebindToLoopback("[::]:9090"))
        assertEquals("127.0.0.1:9090", ConfigInboundCompat.rebindToLoopback("::1:9090"))
    }

    @Test
    fun remoteRuleSetHttpAndMetadataDropped() {
        val root = JSONObject().put(
            "route",
            JSONObject().put(
                "rule_set",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("tag", "ssrf")
                            .put("type", "remote")
                            .put("url", "http://169.254.169.254/latest/meta-data"),
                    )
                    .put(
                        JSONObject()
                            .put("tag", "cleartext")
                            .put("type", "remote")
                            .put("url", "http://example.com/geoip-cn.srs"),
                    )
                    .put(
                        JSONObject()
                            .put("tag", "geosite-cn")
                            .put("type", "remote")
                            .put("url", "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs"),
                    ),
            ),
        )
        assertTrue(ConfigInboundCompat.rewriteRuleSetUrls(root))
        ConfigInboundCompat.healRemoteRuleSets(root)
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        val tags = (0 until sets.length()).map { sets.getJSONObject(it).getString("tag") }
        assertEquals(listOf("geosite-cn"), tags)
        assertTrue(sets.getJSONObject(0).getString("url").startsWith("https://"))
    }

    @Test
    fun clashUiDownloadUrlMustBePublicHttps() {
        val root = JSONObject().put(
            "experimental",
            JSONObject().put(
                "clash_api",
                JSONObject()
                    .put("external_controller", "127.0.0.1:9090")
                    .put("external_ui_download_url", "http://169.254.169.254/ui.zip"),
            ),
        )
        assertTrue(ConfigInboundCompat.sanitizeClashDownloadUrls(root))
        assertFalse(root.getJSONObject("experimental").getJSONObject("clash_api").has("external_ui_download_url"))
    }
}
