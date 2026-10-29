package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Settings
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
    fun dnsProtectKeepsEchRuleAheadOfHttpsReject() {
        // The kernel fetches a node's ECH config with an HTTPS query; if the
        // blanket HTTPS/SVCB reject sits ahead of the ECH domain route, the
        // query is refused and ECH nodes can never connect.
        val echRule = JSONObject()
            .put("domain", JSONArray().put("cloudflare-ech.com"))
            .put("action", "route")
            .put("server", "ech-dns-cloudflare-com")
        val root = JSONObject().put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(JSONObject().put("type", "https").put("tag", "ech-dns-cloudflare-com"))
                        .put(JSONObject().put("type", "local").put("tag", "local")),
                )
                .put("rules", JSONArray().put(echRule)),
        )
        ConfigQuicOverride.applyDnsProtect(root)
        val rules = root.getJSONObject("dns").getJSONArray("rules")
        assertEquals(3, rules.length())
        assertEquals("ech-dns-cloudflare-com", rules.getJSONObject(0).getString("server"))
        assertEquals("reject", rules.getJSONObject(1).getString("action"))
        assertEquals("dns-fakeip", rules.getJSONObject(2).getString("server"))
    }

    @Test
    fun dnsProtectWithoutEchKeepsRejectFirst() {
        val root = JSONObject().put(
            "dns",
            JSONObject().put(
                "rules",
                JSONArray().put(
                    JSONObject().put("domain", JSONArray().put("example.com")).put("server", "local"),
                ),
            ),
        )
        ConfigQuicOverride.applyDnsProtect(root)
        val rules = root.getJSONObject("dns").getJSONArray("rules")
        assertEquals(3, rules.length())
        assertEquals("reject", rules.getJSONObject(0).getString("action"))
        assertEquals("dns-fakeip", rules.getJSONObject(1).getString("server"))
        assertEquals("local", rules.getJSONObject(2).getString("server"))
    }

    @Test
    fun dnsProtectRuleOrderIsIdempotent() {
        val root = JSONObject().put(
            "dns",
            JSONObject().put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("domain", JSONArray().put("cloudflare-ech.com"))
                        .put("action", "route")
                        .put("server", "ech-dns"),
                ),
            ),
        )
        ConfigQuicOverride.applyDnsProtect(root)
        val once = root.getJSONObject("dns").getJSONArray("rules").toString()
        ConfigQuicOverride.applyDnsProtect(root)
        assertEquals(once, root.getJSONObject("dns").getJSONArray("rules").toString())
    }

    @Test
    fun scriptRebuiltDnsKeepsEchRoute() {
        // The bundled overlay script rebuilds dns from scratch: the ECH
        // query server/route the import installed are wiped and its own
        // HTTPS/SVCB reject would starve every ECH node. The startup
        // pipeline re-asserts the route via ensureEchQueryRoute.
        val root = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "vless")
                        .put("tag", "n")
                        .put(
                            "tls",
                            JSONObject().put(
                                "ech",
                                JSONObject()
                                    .put("enabled", true)
                                    .put("query_server_name", "cloudflare-ech.com"),
                            ),
                        ),
                ),
            )
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(JSONObject().put("type", "fakeip").put("tag", "dns-fakeip"))
                            .put(JSONObject().put("type", "udp").put("tag", "dns-cn").put("server", "223.5.5.5"))
                            .put(JSONObject().put("type", "tcp").put("tag", "dns-remote").put("server", "1.1.1.1")),
                    )
                    .put(
                        "rules",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("rule_set", JSONArray().put("geosite-cn"))
                                    .put("server", "dns-cn"),
                            )
                            .put(
                                JSONObject()
                                    .put("query_type", JSONArray().put("HTTPS").put("SVCB"))
                                    .put("action", "reject"),
                            )
                            .put(
                                JSONObject()
                                    .put("query_type", JSONArray().put("A").put("AAAA"))
                                    .put("server", "dns-fakeip"),
                            ),
                    )
                    .put("final", "dns-remote"),
            )
        assertTrue(ConfigIngest.ensureEchQueryRoute(root))
        val dns = root.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        var echServer: JSONObject? = null
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            if (server.getString("tag") == "ech-dns") echServer = server
        }
        assertEquals("https", echServer!!.getString("type"))
        assertEquals("dns.alidns.com", echServer.getString("server"))
        assertFalse(echServer.has("detour"))
        val rules = dns.getJSONArray("rules")
        val first = rules.getJSONObject(0)
        assertEquals("ech-dns", first.getString("server"))
        assertEquals("cloudflare-ech.com", first.getJSONArray("domain").getString(0))
        var rejectIndex = -1
        for (i in 0 until rules.length()) {
            if (rules.getJSONObject(i).optString("action") == "reject") rejectIndex = i
        }
        assertTrue(rejectIndex > 0)
        // Idempotent: a second pass changes nothing.
        val once = dns.toString()
        assertFalse(ConfigIngest.ensureEchQueryRoute(root))
        assertEquals(once, root.getJSONObject("dns").toString())
    }

    @Test
    fun dnsProtectRoutesEchNameForNativeProfile() {
        // A native sing-box profile (like the provider's official JSON) has
        // an ECH outbound but no ech-* DNS rule of its own: without an
        // explicit route, the DNS-leak reject below would swallow the
        // kernel's ECH config query and the node could never connect.
        val root = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "vless")
                        .put("tag", "n")
                        .put(
                            "tls",
                            JSONObject().put(
                                "ech",
                                JSONObject()
                                    .put("enabled", true)
                                    .put("query_server_name", "cloudflare-ech.com"),
                            ),
                        ),
                ),
            )
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("type", "tls").put("tag", "dns_proxy").put("server", "8.8.8.8"),
                    ),
                ),
            )
        ConfigQuicOverride.applyDnsProtect(root)
        val dns = root.getJSONObject("dns")
        val rules = dns.getJSONArray("rules")
        assertEquals("ech-dns", rules.getJSONObject(0).getString("server"))
        assertEquals("reject", rules.getJSONObject(1).getString("action"))
        assertEquals("dns-fakeip", rules.getJSONObject(2).getString("server"))
        val servers = dns.getJSONArray("servers")
        val echDns = (0 until servers.length())
            .map { servers.getJSONObject(it) }
            .first { it.optString("tag") == "ech-dns" }
        assertEquals("dns.alidns.com", echDns.getString("server"))
        assertFalse(echDns.has("detour"))
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

    // ---- China defaults migration (audit F-2/F-4) ----

    private fun migrationWrites(rev: Int, setKeys: Set<String>): Pair<Map<String, Boolean>, Int> {
        val (writes, newRev) = Settings.chinaDefaultsMigration(rev) { it in setKeys }
        return writes.toMap() to newRev
    }

    @Test
    fun chinaDefaultsMigrationFillsUnsetSwitches() {
        val (writes, newRev) = migrationWrites(0, emptySet())
        assertEquals(3, newRev)
        for (key in Settings.CHINA_SAFEGUARD_KEYS) {
            assertEquals("safeguard $key", true, writes[key])
        }
        assertEquals(true, writes[SettingsKey.CONFIG_NORMALIZE])
        assertEquals(false, writes[SettingsKey.STRICT_ROUTE])
    }

    @Test
    fun chinaDefaultsMigrationKeepsDeliberateOff() {
        // A stored key means the user set it — a deliberate "off" on DNS
        // protect / IPv6 must survive the rev<1 tier.
        val setKeys = setOf(SettingsKey.DNS_PROTECT, SettingsKey.DISABLE_IPV6)
        val (writes, newRev) = migrationWrites(0, setKeys)
        assertEquals(3, newRev)
        assertFalse(writes.containsKey(SettingsKey.DNS_PROTECT))
        assertFalse(writes.containsKey(SettingsKey.DISABLE_IPV6))
        assertEquals(true, writes[SettingsKey.CHINA_DIRECT])
    }

    @Test
    fun chinaDefaultsMigrationKeepsDeliberateStrictRoute() {
        // Post-1.0.79 deliberate strict-route users are not forced off.
        val (writes, newRev) = migrationWrites(0, setOf(SettingsKey.STRICT_ROUTE))
        assertEquals(3, newRev)
        assertFalse(writes.containsKey(SettingsKey.STRICT_ROUTE))
    }

    @Test
    fun chinaDefaultsMigrationKeepsDeliberateNormalizeOff() {
        val (writes, _) = migrationWrites(1, setOf(SettingsKey.CONFIG_NORMALIZE))
        assertFalse(writes.containsKey(SettingsKey.CONFIG_NORMALIZE))
    }

    @Test
    fun chinaDefaultsMigrationIsOneShotAtCurrentRev() {
        // Installs already migrated by the old unconditional tiers stay as
        // they are: rev 3 means no writes, ever.
        val (writes, newRev) = migrationWrites(3, emptySet())
        assertEquals(3, newRev)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun chinaDefaultsMigrationResumesFromMiddleRev() {
        val (writes, newRev) = migrationWrites(2, emptySet())
        assertEquals(3, newRev)
        assertEquals(mapOf(SettingsKey.STRICT_ROUTE to false), writes)
    }

    @Test
    fun ensureAndroidTunRebindsLanMixedAndDropsOnlyAppPort() {
        val root = JSONObject()
            .put(
                "inbounds",
                JSONArray()
                    .put(
                        JSONObject().put("type", "tun").put("tag", "tun-in")
                            .put("address", JSONArray().put("172.19.0.1/30")).put("mtu", 1280),
                    )
                    .put(
                        JSONObject().put("type", "mixed").put("tag", "lan-in")
                            .put("listen", "0.0.0.0").put("listen_port", 7890),
                    )
                    .put(
                        JSONObject().put("type", "mixed").put("tag", "app-in")
                            .put("listen", "127.0.0.1").put("listen_port", 17890),
                    ),
            )
        ConfigInboundCompat.ensureAndroidTun(root)
        val inbounds = root.getJSONArray("inbounds")
        val list = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
        val tags = list.map { it.getString("tag") }
        // The inbound survives but is no longer reachable from the LAN: the
        // app never asks the user whether they meant to share their
        // connection, so a 0.0.0.0 listener arriving from a subscription is an
        // open proxy with no UI to show for it. Only the app's own 17890 port
        // is dropped outright.
        assertTrue(tags.contains("lan-in"))
        assertEquals(
            "127.0.0.1",
            list.first { it.getString("tag") == "lan-in" }.getString("listen"),
        )
        assertFalse(tags.contains("app-in"))
        // An explicit smaller MTU is a user choice, not a defect.
        assertEquals(1280, list.first { it.getString("type") == "tun" }.getInt("mtu"))
    }

    @Test
    fun adBlockStillAppliesWhenAdsSetOnlyRoutedToProxy() {
        val root = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(JSONObject().put("type", "vless").put("tag", "proxy")),
            )
            .put(
                "route",
                JSONObject()
                    .put(
                        "rule_set",
                        JSONArray().put(
                            JSONObject()
                                .put("tag", "geosite-category-ads-all").put("type", "remote")
                                .put("url", ConfigAdBlock.RULESET_URL),
                        ),
                    )
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("rule_set", "geosite-category-ads-all").put("outbound", "proxy"),
                        ),
                    ),
            )
        ConfigAdBlock.apply(root)
        val rules = root.getJSONObject("route").getJSONArray("rules")
        // Merely referencing the ads set (routed to a proxy!) must not
        // count as "ads already handled" — the reject rule gets prepended.
        assertEquals("reject", rules.getJSONObject(0).getString("action"))
        assertEquals("geosite-category-ads-all", rules.getJSONObject(0).getString("rule_set"))
    }

    @Test
    fun adBlockDoesNotDuplicateExistingReject() {
        val root = JSONObject()
            .put(
                "route",
                JSONObject()
                    .put(
                        "rule_set",
                        JSONArray().put(
                            JSONObject()
                                .put("tag", "geosite-category-ads-all").put("type", "remote")
                                .put("url", ConfigAdBlock.RULESET_URL),
                        ),
                    )
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("rule_set", JSONArray().put("geosite-category-ads-all"))
                                .put("action", "reject"),
                        ),
                    ),
            )
        ConfigAdBlock.apply(root)
        val rules = root.getJSONObject("route").getJSONArray("rules")
        // The ads set is genuinely rejected already (array-form rule_set):
        // no second reject rule is added.
        assertEquals(1, rules.length())
    }
}
