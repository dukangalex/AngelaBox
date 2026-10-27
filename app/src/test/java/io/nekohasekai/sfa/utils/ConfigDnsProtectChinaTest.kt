package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression (v1.0.133-beta field failure): DNS-protect overwrite mode
 * picked the SUBSCRIPTION's own China DoH server as the protected final
 * resolver. The fixture mirrors the user's subscription: a `dns-cn`
 * server (AliDNS DoH, detour direct) listed BEFORE `dns-remote`, with
 * dns.final already `dns-remote`. The shipped code's candidate scan
 * took the first remote-type server — `dns-cn` — overwrote final to
 * it, and forced its detour from `direct` onto the proxy egress (the
 * two dashboard notices the user screenshotted). China DNS endpoints
 * are direct-reach bootstrap/mirror resolvers: they must be excluded
 * from final candidacy and from detour forcing alike.
 */
class ConfigDnsProtectChinaTest {

    private fun server(root: JSONObject, tag: String): JSONObject {
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        for (i in 0 until servers.length()) {
            val s = servers.getJSONObject(i)
            if (s.optString("tag") == tag) return s
        }
        throw AssertionError("server $tag not found")
    }

    /** The user's subscription shape: dns-cn first, dns-remote second, final=dns-remote. */
    private fun userFixture(): JSONObject = JSONObject()
        .put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "https")
                                .put("tag", "dns-cn")
                                .put("server", "dns.alidns.com")
                                .put("detour", "direct"),
                        )
                        .put(
                            JSONObject()
                                .put("type", "https")
                                .put("tag", "dns-remote")
                                .put("server", "cloudflare-dns.com")
                                .put("detour", "direct"),
                        ),
                )
                .put("final", "dns-remote"),
        )
        .put(
            "outbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "selector")
                        .put("tag", "proxy")
                        .put("outbounds", JSONArray().put("n1")),
                )
                .put(JSONObject().put("type", "vless").put("tag", "n1").put("server", "example.com").put("server_port", 443))
                .put(JSONObject().put("type", "direct").put("tag", "direct")),
        )
        .put("route", JSONObject().put("final", "proxy"))

    @Test
    fun chinaDohIsNotPickedAsProtectedFinal() {
        val root = userFixture()
        val notices = mutableListOf<OverrideNotice>()
        ConfigQuicOverride.applyDnsProtect(
            root,
            overwriteFinal = true,
            egressTag = "proxy",
            notices = notices,
        )
        val dns = root.getJSONObject("dns")
        // Final stays the subscription's real remote resolver.
        assertEquals("dns-remote", dns.getString("final"))
        // The China server is left exactly as the subscription wrote it.
        assertEquals("direct", server(root, "dns-cn").getString("detour"))
        // Detour forcing still lands on the actual protected server.
        assertEquals("proxy", server(root, "dns-remote").getString("detour"))
        // No notice may claim dns-cn was rewritten.
        assertTrue(notices.none { it.reason.contains("dns-cn") })
        assertTrue(notices.any { it.reason.contains("dns-remote") && it.reason.contains("detour") })
    }

    @Test
    fun chinaUdpIpServerIsNotPickedEither() {
        val root = JSONObject()
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("type", "udp")
                                    .put("tag", "dns-cn")
                                    .put("server", "223.5.5.5")
                                    .put("detour", "direct"),
                            )
                            .put(
                                JSONObject()
                                    .put("type", "tls")
                                    .put("tag", "dns-remote")
                                    .put("server", "8.8.8.8"),
                            ),
                    )
                    .put("final", "dns-remote"),
            )
            .put("route", JSONObject().put("final", "proxy"))
        ConfigQuicOverride.applyDnsProtect(root, overwriteFinal = true, egressTag = "proxy")
        assertEquals("dns-remote", root.getJSONObject("dns").getString("final"))
        assertEquals("direct", server(root, "dns-cn").getString("detour"))
    }

    @Test
    fun onlyChinaRemoteFallsBackToCreatedSysRemote() {
        // No non-China remote server exists: the design says only then
        // may the code create sys-dns-remote (8.8.8.8 DoH) as final —
        // it must not "rescue" the situation by promoting dns-cn.
        val root = JSONObject()
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("type", "https")
                                    .put("tag", "dns-cn")
                                    .put("server", "dns.alidns.com")
                                    .put("detour", "direct"),
                            )
                            .put(JSONObject().put("type", "local").put("tag", "local")),
                    )
                    .put("final", "dns-cn"),
            )
            .put("route", JSONObject().put("final", "proxy"))
        ConfigQuicOverride.applyDnsProtect(root, overwriteFinal = true, egressTag = "proxy")
        val dns = root.getJSONObject("dns")
        assertEquals("sys-dns-remote", dns.getString("final"))
        val created = server(root, "sys-dns-remote")
        assertEquals("https", created.getString("type"))
        assertEquals("8.8.8.8", created.getString("server"))
        assertEquals("proxy", created.getString("detour"))
        assertEquals("direct", server(root, "dns-cn").getString("detour"))
    }

    @Test
    fun chinaDohByDomainSuffixIsRecognized() {
        // doh.pub (DNSPod) behind an arbitrary tag, listed first.
        val root = JSONObject()
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("type", "https")
                                    .put("tag", "bootstrap")
                                    .put("server", "doh.pub")
                                    .put("detour", "direct"),
                            )
                            .put(
                                JSONObject()
                                    .put("type", "https")
                                    .put("tag", "dns-remote")
                                    .put("server", "dns.google"),
                            ),
                    )
                    .put("final", "dns-remote"),
            )
            .put("route", JSONObject().put("final", "proxy"))
        ConfigQuicOverride.applyDnsProtect(root, overwriteFinal = true, egressTag = "proxy")
        assertEquals("dns-remote", root.getJSONObject("dns").getString("final"))
        assertEquals("direct", server(root, "bootstrap").getString("detour"))
        assertFalse(server(root, "dns-remote").getString("detour") == "direct")
    }
}
