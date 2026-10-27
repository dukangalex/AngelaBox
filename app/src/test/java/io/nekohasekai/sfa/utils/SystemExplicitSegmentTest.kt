package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design v2 §2.2: the system-explicit segment is the pipeline's last
 * route/dns stage and applies unconditionally — its API takes no script
 * state at all, so the pre-v2 failure mode (a bound script silently
 * switching all seven route-class toggles off via `!scriptOn`) cannot
 * come back through this class. The pipeline-level wiring is pinned by
 * scripts/test_override_guards.py.
 */
class SystemExplicitSegmentTest {

    private fun config(
        chinaIpDomainDirect: Boolean = false,
        chinaPublicDnsDirect: Boolean = false,
        lanDirect: Boolean = false,
        privacyProtect: Boolean = false,
        vpnLeakProtect: Boolean = false,
        disableQuic: Boolean = false,
        excludeCnQuic: Boolean = false,
        dnsProtect: Boolean = false,
        dnsProtectLegacyFillOnly: Boolean = false,
        disableIpv6: Boolean = false,
        webrtcProtect: Boolean = false,
        adsBlock: Boolean = false,
    ) = SystemExplicitSegment.Config(
        chinaIpDomainDirect = chinaIpDomainDirect,
        chinaPublicDnsDirect = chinaPublicDnsDirect,
        lanDirect = lanDirect,
        privacyProtect = privacyProtect,
        vpnLeakProtect = vpnLeakProtect,
        disableQuic = disableQuic,
        excludeCnQuic = excludeCnQuic,
        dnsProtect = dnsProtect,
        dnsProtectLegacyFillOnly = dnsProtectLegacyFillOnly,
        disableIpv6 = disableIpv6,
        webrtcProtect = webrtcProtect,
        adsBlock = adsBlock,
    )

    private val allOn = config(
        chinaIpDomainDirect = true,
        chinaPublicDnsDirect = true,
        lanDirect = true,
        privacyProtect = true,
        vpnLeakProtect = true,
        disableQuic = true,
        excludeCnQuic = true,
        dnsProtect = true,
        disableIpv6 = true,
        webrtcProtect = true,
        adsBlock = true,
    )

    /** Runs the segment stages in the same order ConfigQuicOverride.apply does. */
    private fun runSegment(
        root: JSONObject,
        cfg: SystemExplicitSegment.Config,
        chainTag: String? = null,
    ): SystemExplicitSegment {
        val segment = SystemExplicitSegment(root, cfg, chainTag)
        segment.applyChinaFamily()
        segment.applyVpnLeakProtect()
        segment.applyQuicToggle()
        segment.applyDnsProtectToggle()
        segment.applyDisableIpv6Toggle()
        segment.applyPrivacyToggle()
        segment.applyWebrtcToggle()
        segment.applyAdsToggle()
        segment.assembleRouteRules()
        return segment
    }

    private fun baseRoot(): JSONObject = JSONObject(
        """
        {
          "outbounds": [
            {"type": "selector", "tag": "proxy", "outbounds": ["node"]},
            {"type": "vless", "tag": "node", "server": "a.example"},
            {"type": "direct", "tag": "direct"}
          ],
          "route": {
            "final": "proxy",
            "rules": [
              {"domain_suffix": ["example.org"], "outbound": "proxy"}
            ]
          },
          "dns": {
            "servers": [
              {"type": "https", "tag": "remote", "server": "dns.example.com", "detour": "proxy"}
            ],
            "final": "remote",
            "rules": []
          },
          "inbounds": [
            {"type": "tun", "tag": "tun-in", "inet6_address": "fdfe:dcba:9876::1/126"}
          ]
        }
        """.trimIndent(),
    )

    private fun rules(root: JSONObject): List<JSONObject> {
        val arr = root.getJSONObject("route").getJSONArray("rules")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun JSONArray?.hasString(value: String): Boolean {
        if (this == null) return false
        return (0 until length()).any { optString(it) == value }
    }

    /** rule_set appears both as a plain string and as an array in the wild. */
    private fun JSONObject.ruleSetMentions(tag: String): Boolean =
        when (val rs = opt("rule_set")) {
            is String -> rs == tag
            is JSONArray -> rs.hasString(tag)
            else -> false
        }

    private fun JSONObject.portIs(port: Int): Boolean = optInt("port", -1) == port

    private fun JSONObject.portsContain(port: Int): Boolean {
        val arr = optJSONArray("port") ?: return false
        return (0 until arr.length()).any { arr.optInt(it, -1) == port }
    }

    @Test
    fun segmentHeadOrderWithAllTogglesOn() {
        val root = baseRoot()
        runSegment(root, allOn)
        val rs = rules(root)
        fun idx(name: String, pred: (JSONObject) -> Boolean): Int {
            val i = rs.indexOfFirst(pred)
            assertTrue("missing rule: $name", i >= 0)
            return i
        }
        // Design §2.2 order table #1-#10, then the subscription's own rule.
        val hijackProto = idx("hijack protocol") {
            it.optString("action") == "hijack-dns" && it.has("protocol")
        }
        val hijack53 = idx("hijack port 53") {
            it.optString("action") == "hijack-dns" && it.portIs(53)
        }
        val sniff = idx("sniff") { it.optString("action") == "sniff" }
        val webrtcUdp = idx("webrtc udp") {
            it.optString("network") == "udp" && it.portsContain(3478)
        }
        val webrtcTcp = idx("webrtc tcp") {
            it.optString("network") == "tcp" && it.portsContain(3478)
        }
        val webrtcKw = idx("webrtc keyword") { it.has("domain_keyword") }
        val webrtcStun = idx("webrtc stun") { it.optJSONArray("protocol").hasString("stun") }
        val privacy = idx("privacy discovery") { it.portsContain(5353) }
        val ipv6 = idx("ipv6 reject") { it.optInt("ip_version", -1) == 6 }
        val dot = idx("853 reject") { it.portIs(853) && it.optString("action") == "reject" }
        val quicCnIp = idx("quic cn ip exception") {
            it.ruleSetMentions("geoip-cn") && it.portIs(443) && it.has("outbound")
        }
        val quicCnSite = idx("quic cn site exception") {
            it.ruleSetMentions("geosite-cn") && it.portIs(443) && it.has("outbound")
        }
        val quic443 = idx("quic 443 reject") {
            it.portIs(443) && it.optString("action") == "reject"
        }
        val lanPriv = idx("lan private") { it.optBoolean("ip_is_private") }
        val lanCidr = idx("lan cidr") { it.optJSONArray("ip_cidr").hasString("100.64.0.0/10") }
        val lanDomain = idx("lan domain") { it.optJSONArray("domain").hasString("localhost") }
        val pubIp = idx("public dns ip") {
            it.optJSONArray("ip_cidr").hasString("223.5.5.5/32")
        }
        val pubDomain = idx("public dns domain") {
            it.optJSONArray("domain").hasString("dns.alidns.com")
        }
        val chinaSet = idx("china rule_set") {
            it.ruleSetMentions("geosite-cn") && it.ruleSetMentions("geoip-cn") &&
                it.optString("outbound") == "direct" && !it.has("port")
        }
        val chinaSuffix = idx("china suffix fallback") {
            it.optJSONArray("domain_suffix").hasString("cn") &&
                it.optString("outbound") == "direct"
        }
        val ads = idx("ads reject") {
            it.optString("rule_set") == "geosite-category-ads-all" &&
                it.optString("action") == "reject"
        }
        val subscription = idx("subscription rule") {
            it.optJSONArray("domain_suffix").hasString("example.org")
        }
        val order = listOf(
            hijackProto, hijack53, sniff, webrtcUdp, webrtcTcp, webrtcKw, webrtcStun,
            privacy, ipv6, dot, quicCnIp, quicCnSite, quic443,
            lanPriv, lanCidr, lanDomain, pubIp, pubDomain, chinaSet, chinaSuffix,
            ads, subscription,
        )
        assertEquals("segment head must follow the design order table", order.sorted(), order)
        assertEquals(0, hijackProto)
        // IPv6 disabling also did its non-rule work.
        assertEquals("ipv4_only", root.getJSONObject("dns").getString("strategy"))
        assertFalse(root.getJSONArray("inbounds").getJSONObject(0).has("inet6_address"))
    }

    @Test
    fun dnsFinalOverwriteEmitsNotice() {
        // A script (or subscription) pointed final at the local resolver:
        // overwrite mode moves it to the protected server and says so.
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "selector", "tag": "proxy", "outbounds": ["node"]},
                {"type": "vless", "tag": "node", "server": "a.example"}
              ],
              "route": {"final": "proxy", "rules": []},
              "dns": {
                "servers": [
                  {"type": "local", "tag": "local"},
                  {"type": "https", "tag": "remote", "server": "dns.example.com"}
                ],
                "final": "local",
                "rules": []
              }
            }
            """.trimIndent(),
        )
        val segment = runSegment(root, config(dnsProtect = true))
        val dns = root.getJSONObject("dns")
        assertEquals("remote", dns.getString("final"))
        val remote = dns.getJSONArray("servers").getJSONObject(2)
        assertEquals("remote", remote.getString("tag"))
        assertEquals("proxy", remote.getString("detour"))
        assertTrue(segment.notices.any { it.reason.contains("local") && it.reason.contains("remote") })
    }

    @Test
    fun cnMirrorRulesSitBeforeFakeIpRule() {
        val root = baseRoot()
        runSegment(root, config(chinaIpDomainDirect = true, dnsProtect = true))
        val dnsRules = root.getJSONObject("dns").getJSONArray("rules")
        val list = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }
        val mirrorIdx = list.indexOfFirst { it.optString("server") == "sys-cn-dns" }
        val logicalIdx = list.indexOfFirst {
            it.optString("type") == "logical" && it.optString("server") == "sys-cn-dns"
        }
        val fakeIdx = list.indexOfFirst { it.optString("server") == "dns-fakeip" }
        assertTrue(mirrorIdx >= 0)
        assertTrue(logicalIdx >= 0)
        assertTrue("mirror must precede fakeip or CN queries get fake answers", mirrorIdx < fakeIdx)
        assertTrue(logicalIdx < fakeIdx)
        // The logical rule has the official shape: NOT geolocation-!cn AND geoip-cn.
        val nested = list[logicalIdx].getJSONArray("rules")
        assertEquals("geosite-geolocation-!cn", nested.getJSONObject(0).getString("rule_set"))
        assertTrue(nested.getJSONObject(0).getBoolean("invert"))
        assertEquals("geoip-cn", nested.getJSONObject(1).getString("rule_set"))
    }

    @Test
    fun sysCnDnsSurvivesBrokenDetourStrip() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "selector", "tag": "proxy", "outbounds": ["node"]},
                {"type": "vless", "tag": "node", "server": "a.example"},
                {"type": "direct", "tag": "direct"},
                {"type": "direct", "tag": "plain-empty"}
              ],
              "route": {"final": "proxy", "rules": []},
              "dns": {
                "servers": [
                  {"type": "udp", "tag": "bad", "server": "9.9.9.9", "detour": "plain-empty"}
                ],
                "rules": []
              }
            }
            """.trimIndent(),
        )
        runSegment(root, config(chinaIpDomainDirect = true))
        val dns = root.getJSONObject("dns")
        val servers = (0 until dns.getJSONArray("servers").length())
            .map { dns.getJSONArray("servers").getJSONObject(it) }
        val sysCn = servers.first { it.optString("tag") == "sys-cn-dns" }
        assertEquals("223.5.5.5", sysCn.getString("server"))
        assertEquals("direct", sysCn.getString("detour"))
        // The guaranteed direct is non-empty now (domain_resolver attached).
        val direct = root.getJSONArray("outbounds").getJSONObject(2)
        assertTrue(direct.has("domain_resolver"))
        // Control: the strip really runs — the plain server's detour to the
        // other (still empty) direct is removed, sys-cn-dns is exempt.
        ConfigCompat.stripBrokenDnsDetours(root)
        val after = (0 until dns.getJSONArray("servers").length())
            .map { dns.getJSONArray("servers").getJSONObject(it) }
        assertEquals("direct", after.first { it.optString("tag") == "sys-cn-dns" }.getString("detour"))
        assertFalse(after.first { it.optString("tag") == "bad" }.has("detour"))
    }

    @Test
    fun vpnLeakKeepsChainPinnedFinalButOverwritesDirectFinal() {
        val chainTag = "chainbox-chain-1-2"
        val pinned = baseRoot()
        pinned.getJSONObject("route").put("final", chainTag)
        val segPinned = SystemExplicitSegment(pinned, config(vpnLeakProtect = true), chainTag)
        segPinned.applyVpnLeakProtect()
        assertEquals(chainTag, pinned.getJSONObject("route").getString("final"))
        assertTrue(segPinned.notices.none { it.reason.contains("最终出站") })

        val directFinal = baseRoot()
        directFinal.getJSONObject("route").put("final", "direct")
        val segDirect = SystemExplicitSegment(directFinal, config(vpnLeakProtect = true), null)
        segDirect.applyVpnLeakProtect()
        assertEquals("proxy", directFinal.getJSONObject("route").getString("final"))
        assertTrue(segDirect.notices.any { it.reason.contains("最终出站") })
    }

    @Test
    fun legacyFillOnlyKeepsPresetDnsFinal() {
        fun fixture() = JSONObject(
            """
            {
              "dns": {
                "servers": [
                  {"type": "https", "tag": "mine", "server": "a.example"},
                  {"type": "https", "tag": "other", "server": "b.example"}
                ],
                "final": "other",
                "rules": []
              }
            }
            """.trimIndent(),
        )
        val legacy = fixture()
        ConfigQuicOverride.applyDnsProtect(legacy, overwriteFinal = false)
        assertEquals("other", legacy.getJSONObject("dns").getString("final"))
        val overwrite = fixture()
        ConfigQuicOverride.applyDnsProtect(overwrite)
        assertEquals("mine", overwrite.getJSONObject("dns").getString("final"))
    }

    @Test
    fun splitKeysGateTheirBlocksIndependently() {
        // LAN only: no China entities, no public-DNS block.
        val lanOnly = baseRoot()
        runSegment(lanOnly, config(lanDirect = true))
        val lanRules = rules(lanOnly)
        assertTrue(lanRules.any { it.optBoolean("ip_is_private") })
        assertFalse(lanRules.any { it.optJSONArray("ip_cidr").hasString("223.5.5.5/32") })
        assertFalse(lanRules.any { it.has("rule_set") })
        val lanServers = lanOnly.getJSONObject("dns").getJSONArray("servers")
        assertFalse(
            (0 until lanServers.length()).any {
                lanServers.getJSONObject(it).optString("tag") == "sys-cn-dns"
            },
        )

        // Public DNS only: its block lands, but no rule-set definitions are
        // injected and no LAN block appears.
        val pubOnly = baseRoot()
        runSegment(pubOnly, config(chinaPublicDnsDirect = true))
        val pubRules = rules(pubOnly)
        assertTrue(pubRules.any { it.optJSONArray("ip_cidr").hasString("223.5.5.5/32") })
        assertFalse(pubRules.any { it.optBoolean("ip_is_private") })
        assertFalse(pubOnly.getJSONObject("route").has("rule_set"))

        // China IP/domain only: entities + block land, LAN/public blocks don't.
        val cnOnly = baseRoot()
        runSegment(cnOnly, config(chinaIpDomainDirect = true))
        val cnRules = rules(cnOnly)
        assertTrue(cnRules.any { it.ruleSetMentions("geoip-cn") && !it.has("port") })
        assertFalse(cnRules.any { it.optBoolean("ip_is_private") })
        assertFalse(cnRules.any { it.optJSONArray("ip_cidr").hasString("223.5.5.5/32") })
        val defs = cnOnly.getJSONObject("route").getJSONArray("rule_set")
        val tags = (0 until defs.length()).map { defs.getJSONObject(it).getString("tag") }
        assertTrue(tags.containsAll(listOf("geosite-cn", "geoip-cn", "geosite-geolocation-!cn")))
        val cnServers = cnOnly.getJSONObject("dns").getJSONArray("servers")
        assertTrue(
            (0 until cnServers.length()).any {
                cnServers.getJSONObject(it).optString("tag") == "sys-cn-dns"
            },
        )
    }

    @Test
    fun sysDnsRemoteCreatedWhenOnlyLocalServer() {
        val root = JSONObject(
            """
            {
              "outbounds": [
                {"type": "selector", "tag": "proxy", "outbounds": ["node"]},
                {"type": "vless", "tag": "node", "server": "a.example"}
              ],
              "route": {"final": "proxy", "rules": []},
              "dns": {
                "servers": [{"type": "local", "tag": "local"}],
                "rules": []
              }
            }
            """.trimIndent(),
        )
        runSegment(root, config(dnsProtect = true))
        val dns = root.getJSONObject("dns")
        assertEquals("sys-dns-remote", dns.getString("final"))
        val servers = (0 until dns.getJSONArray("servers").length())
            .map { dns.getJSONArray("servers").getJSONObject(it) }
        val created = servers.first { it.optString("tag") == "sys-dns-remote" }
        assertEquals("https", created.getString("type"))
        assertEquals("8.8.8.8", created.getString("server"))
        assertEquals("proxy", created.getString("detour"))
    }

    @Test
    fun existingTailSniffIsDedupedIntoWebrtcHead() {
        val root = baseRoot()
        val rs = root.getJSONObject("route").getJSONArray("rules")
        rs.put(JSONObject().put("action", "sniff"))
        runSegment(root, config(webrtcProtect = true))
        val all = rules(root)
        val sniffs = all.filter { it.optString("action") == "sniff" }
        assertEquals(1, sniffs.size)
        // The surviving sniff-all is the block copy at the WebRTC head.
        val stunIdx = all.indexOfFirst { it.optJSONArray("protocol").hasString("stun") }
        assertEquals(all.indexOf(sniffs[0]) + 1, all.indexOfFirst {
            it.optString("network") == "udp" && it.portsContain(3478)
        })
        assertTrue(stunIdx > all.indexOf(sniffs[0]))
    }
}
