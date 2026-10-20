package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun cacheFileUsesOfficialDefaultsWhenMissing() {
        val root = JSONObject()
        assertTrue(ConfigInboundCompat.ensureCacheFile(root, "7"))
        val cache = root.getJSONObject("experimental").getJSONObject("cache_file")
        assertTrue(cache.getBoolean("enabled"))
        assertEquals("profile-7", cache.getString("cache_id"))
        assertTrue(cache.getBoolean("store_dns"))
        assertTrue(cache.getBoolean("store_fakeip"))
        assertEquals("1m", cache.getString("flush_interval"))
        assertFalse(ConfigInboundCompat.ensureCacheFile(root, "7"))
    }

    @Test
    fun cacheFileLeavesAnExplicitDisable() {
        val root = JSONObject().put(
            "experimental",
            JSONObject().put("cache_file", JSONObject().put("enabled", false)),
        )
        assertFalse(ConfigInboundCompat.ensureCacheFile(root, "7"))
        val cache = root.getJSONObject("experimental").getJSONObject("cache_file")
        assertFalse(cache.getBoolean("enabled"))
        assertFalse(cache.has("store_dns"))
    }

    @Test
    fun cacheFileKeepsAUserFlushInterval() {
        val root = JSONObject().put(
            "experimental",
            JSONObject().put(
                "cache_file",
                JSONObject().put("enabled", true).put("flush_interval", "5m").put("cache_id", "mine"),
            ),
        )
        assertTrue(ConfigInboundCompat.ensureCacheFile(root, "7"))
        val cache = root.getJSONObject("experimental").getJSONObject("cache_file")
        assertEquals("5m", cache.getString("flush_interval"))
        assertEquals("mine", cache.getString("cache_id"))
        assertTrue(cache.getBoolean("store_dns"))
    }

    @Test
    fun directHttpClientGetsAConnectTimeout() {
        val root = JSONObject().put(
            "http_clients",
            JSONArray().put(JSONObject().put("tag", "http-direct")),
        )
        ConfigInboundCompat.ensureDirectHttpTimeout(root)
        assertEquals(
            "10s",
            root.getJSONArray("http_clients").getJSONObject(0).getString("connect_timeout"),
        )
        ConfigInboundCompat.ensureDirectHttpTimeout(root)
        assertEquals(
            "10s",
            root.getJSONArray("http_clients").getJSONObject(0).getString("connect_timeout"),
        )
    }

    @Test
    fun dnsProtectOverwritesExisting() {
        val root = JSONObject()
            .put("dns", JSONObject().put("strategy", "ipv6_only"))
            .put("route", JSONObject().put("auto_detect_interface", false))
        ConfigQuicOverride.applyDnsProtect(root)
        assertFalse(root.getJSONObject("dns").has("independent_cache"))
        assertEquals("ipv6_only", root.getJSONObject("dns").getString("strategy"))
        assertTrue(root.getJSONObject("route").getBoolean("auto_detect_interface"))
    }

    @Test
    fun dnsProtectAppliesResilienceDefaults() {
        val root = JSONObject()
        ConfigQuicOverride.applyDnsProtect(root)
        val dns = root.getJSONObject("dns")
        assertTrue(dns.getBoolean("optimistic"))
        assertEquals("5s", dns.getString("timeout"))
        assertEquals(2048, dns.getInt("cache_capacity"))
    }

    @Test
    fun dnsProtectKeepsUserResilienceValues() {
        val root = JSONObject().put(
            "dns",
            JSONObject()
                .put("optimistic", false)
                .put("timeout", "10s")
                .put("cache_capacity", 4096),
        )
        ConfigQuicOverride.applyDnsProtect(root)
        val dns = root.getJSONObject("dns")
        assertFalse(dns.getBoolean("optimistic"))
        assertEquals("10s", dns.getString("timeout"))
        assertEquals(4096, dns.getInt("cache_capacity"))
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
    fun strictRouteOffClearsTun() {
        val root = JSONObject().put(
            "inbounds",
            JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun-in").put("strict_route", true)),
        )
        ConfigQuicOverride.applyStrictRoute(root, false)
        assertFalse(root.getJSONArray("inbounds").getJSONObject(0).getBoolean("strict_route"))
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

    @Test
    fun ensureTunRewritesBadInterfaceAndDropsMixed() {
        val root = JSONObject().put(
            "inbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("inet6_address", "fdfe:dcba:9876::1/126")
                        .put("stack", "gvisor")
                        .put("mtu", 9000)
                        .put("auto_route", false),
                )
                .put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "mixed-in")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", 17890),
                ),
        )
        ConfigInboundCompat.ensureAndroidTun(root)
        val inbounds = root.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val tun = inbounds.getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals("172.19.0.1/30", tun.getJSONArray("address").getString(0))
        assertFalse(tun.has("inet6_address"))
        assertFalse(tun.has("stack"))
        assertEquals(1500, tun.getInt("mtu"))
        assertTrue(tun.getBoolean("auto_route"))
        assertEquals("hijack", tun.getString("dns_mode"))
        ConfigQuicOverride.applyStrictRoute(root)
        assertTrue(tun.getBoolean("strict_route"))
    }

    @Test
    fun ensureTunInsertsOneWhenMissing() {
        val root = JSONObject()
        ConfigInboundCompat.ensureAndroidTun(root)
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun-in", tun.getString("tag"))
        assertEquals("172.19.0.1/30", tun.getJSONArray("address").getString(0))
        assertEquals(1500, tun.getInt("mtu"))
        assertEquals("hijack", tun.getString("dns_mode"))
        ConfigQuicOverride.applyStrictRoute(root)
        assertTrue(tun.getBoolean("strict_route"))
    }

    @Test
    fun dnsProtectWritesRealFakeIpProtection() {
        val root = JSONObject()
        ConfigQuicOverride.applyDnsProtect(root)
        val dns = root.getJSONObject("dns")
        // fakeip 服务器
        val servers = dns.getJSONArray("servers")
        val fakeIp = (0 until servers.length()).map { servers.getJSONObject(it) }
            .first { it.optString("type") == "fakeip" }
        assertEquals("dns-fakeip", fakeIp.getString("tag"))
        assertEquals("198.18.0.0/15", fakeIp.getString("inet4_range"))
        // final 指向真实上游，不能是 fakeip
        assertEquals("dns-remote", dns.getString("final"))
        // DNS 规则：A/AAAA 走 fakeip，HTTPS/SVCB 拒绝
        val rules = dns.getJSONArray("rules")
        assertTrue(
            (0 until rules.length()).any {
                rules.getJSONObject(it).optString("server") == "dns-fakeip"
            },
        )
        assertTrue(
            (0 until rules.length()).any {
                val r = rules.getJSONObject(it)
                r.optString("action") == "reject" &&
                    r.getJSONArray("query_type").toString().contains("HTTPS")
            },
        )
        // 路由：53 端口劫持
        val routeRules = root.getJSONObject("route").getJSONArray("rules")
        assertTrue(
            (0 until routeRules.length()).any {
                routeRules.getJSONObject(it).optString("action") == "hijack-dns"
            },
        )
    }

    @Test
    fun dnsProtectIsIdempotentAndKeepsUserDns() {
        val root = JSONObject().put(
            "dns",
            JSONObject().put(
                "servers",
                org.json.JSONArray().put(
                    JSONObject().put("type", "https").put("tag", "mine")
                        .put("server", "dns.example.com"),
                ),
            ),
        )
        ConfigQuicOverride.applyDnsProtect(root)
        val first = root.toString()
        ConfigQuicOverride.applyDnsProtect(root)
        assertEquals(first, root.toString())
        val dns = root.getJSONObject("dns")
        // 用户自带的 DNS 服务器保留，final 指向它而不是我们补的
        assertEquals("mine", dns.getString("final"))
        val servers = dns.getJSONArray("servers")
        assertTrue((0 until servers.length()).any { servers.getJSONObject(it).optString("tag") == "mine" })
        assertTrue((0 until servers.length()).any { servers.getJSONObject(it).optString("tag") == "dns-fakeip" })
    }
}

