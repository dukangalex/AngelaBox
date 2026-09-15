package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRuntimeCompilerTest {

    private fun profile(finalTag: String = "漏网之鱼"): String {
        return JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("hk-1"))
                    .put(node("jp-1"))
                    .put(
                        JSONObject()
                            .put("type", "selector")
                            .put("tag", "节点选择")
                            .put("outbounds", JSONArray().put("hk-1").put("jp-1")),
                    )
                    .put(
                        JSONObject()
                            .put("type", "selector")
                            .put("tag", "漏网之鱼")
                            .put("outbounds", JSONArray().put("节点选择").put("direct")),
                    )
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put("route", JSONObject().put("final", finalTag))
            .toString()
    }

    private fun node(tag: String) = JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", "example.com")
        .put("server_port", 443)

    @Test
    fun resolveMainTagSkipsFinalLikeRoute() {
        val outs = JSONObject(profile()).getJSONArray("outbounds")
        val tag = ChainRuntimeCompiler.resolveMainTag(outs, "漏网之鱼")
        assertEquals("节点选择", tag)
    }

    @Test
    fun sameProfileChainStripsDirectAndStarts() {
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = profile(),
                currentProfileId = 1L,
                entryTag = "漏网之鱼",
                landingProfileId = 1L,
                landingTag = "jp-1",
                landingContent = null,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        assertEquals("chain", chain.optString("type"))
        assertFalse(chain.has("fail_closed"))
        val hops = chain.getJSONArray("outbounds")
        assertEquals(2, hops.length())
        assertEquals("漏网之鱼", hops.getString(0))
        val entry = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("tag") == "漏网之鱼" }
        val members = (0 until entry.getJSONArray("outbounds").length()).map {
            entry.getJSONArray("outbounds").getString(it)
        }
        assertFalse(members.contains("direct"))
        assertTrue(members.contains("节点选择"))
        assertEquals("jp-1", hops.getString(1))
        assertTrue(root.getJSONObject("route").getString("final").startsWith("chainbox-chain-"))
        assertTrue(tagsNoneStartWithEntryClone(outs))
    }

    private fun tagsNoneStartWithEntryClone(outs: JSONArray): Boolean {
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }
        return tags.none { it.startsWith("chainbox-entry-") }
    }

    @Test
    fun autoEntryDoesNotLockToFinalGroup() {
        val outs = JSONObject(profile()).getJSONArray("outbounds")
        assertNotEquals("漏网之鱼", ChainRuntimeCompiler.resolveMainTag(outs, "漏网之鱼"))
        assertTrue(ChainRuntimeCompiler.isFinalLike("漏网之鱼"))
        assertTrue(ChainRuntimeCompiler.isFinalLike("final"))
    }

    @Test
    fun crossProfileLandingKeepsSeparateTags() {
        val landing = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("zgo-node"))
                    .put(
                        JSONObject()
                            .put("type", "urltest")
                            .put("tag", "zgo")
                            .put("outbounds", JSONArray().put("zgo-node")),
                    ),
            )
            .toString()
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = profile("节点选择"),
                currentProfileId = 11L,
                entryTag = "节点选择",
                landingProfileId = 99L,
                landingTag = "zgo",
                landingContent = landing,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }
        assertTrue(tags.any { it.startsWith("chainbox-landing-99-") })
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        val hops = chain.getJSONArray("outbounds")
        assertEquals("节点选择", hops.getString(0))
        assertEquals("chainbox-landing-99-zgo", hops.getString(1))
    }

    @Test
    fun missingSavedEntryFallsBackAfterSubscriptionUpdate() {
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = profile("节点选择"),
                currentProfileId = 1L,
                entryTag = "旧分组名已不存在",
                landingProfileId = 1L,
                landingTag = "jp-1",
                landingContent = null,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        val hops = chain.getJSONArray("outbounds")
        assertEquals("节点选择", hops.getString(0))
        assertEquals("jp-1", hops.getString(1))
    }

    @Test
    fun pinTrafficRewritesProxyRoutesAndDnsDetour() {
        val src = JSONObject(profile("节点选择"))
        src.getJSONObject("route").put(
            "rules",
            JSONArray()
                .put(JSONObject().put("clash_mode", "Global").put("outbound", "节点选择"))
                .put(JSONObject().put("geosite", "cn").put("outbound", "direct"))
                .put(JSONObject().put("domain_suffix", "google.com").put("outbound", "direct")),
        )
        src.put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject().put("tag", "remote").put("address", "8.8.8.8").put("detour", "节点选择"),
                ),
            ),
        )
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = src.toString(),
                currentProfileId = 1L,
                entryTag = "节点选择",
                landingProfileId = 1L,
                landingTag = "jp-1",
                landingContent = null,
            ),
        )
        val root = JSONObject(compiled)
        val chainTag = root.getJSONObject("route").getString("final")
        assertTrue(chainTag.startsWith("chainbox-chain-"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals(chainTag, rules.getJSONObject(0).getString("outbound"))
        assertEquals("direct", rules.getJSONObject(1).getString("outbound"))
        assertEquals(chainTag, rules.getJSONObject(2).getString("outbound"))
        val detour = root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour")
        assertEquals("节点选择", detour)
        val outs = root.getJSONArray("outbounds")
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        assertEquals("节点选择", chain.getJSONArray("outbounds").getString(0))
        val proxyOutbounds = (0 until rules.length()).map { rules.getJSONObject(it).optString("outbound") }
        assertFalse(proxyOutbounds.contains("节点选择"))
    }

    @Test
    fun pinTrafficKeepsNonEntryDnsDetour() {
        val src = JSONObject(profile("节点选择"))
        src.put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("tag", "via-entry").put("address", "8.8.8.8").put("detour", "节点选择"))
                    .put(JSONObject().put("tag", "via-land").put("address", "1.1.1.1").put("detour", "jp-1")),
            ),
        )
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = src.toString(),
                currentProfileId = 1L,
                entryTag = "节点选择",
                landingProfileId = 1L,
                landingTag = "jp-1",
                landingContent = null,
            ),
        )
        val servers = JSONObject(compiled).getJSONObject("dns").getJSONArray("servers")
        assertEquals("节点选择", servers.getJSONObject(0).getString("detour"))
        assertEquals("jp-1", servers.getJSONObject(1).getString("detour"))
    }

    @Test
    fun emptyCrossProfileLandingContentFailsClosed() {
        try {
            ChainRuntimeCompiler.apply(
                ChainRuntimeCompiler.ApplyRequest(
                    content = profile("节点选择"),
                    currentProfileId = 1L,
                    entryTag = "节点选择",
                    landingProfileId = 99L,
                    landingTag = "zgo",
                    landingContent = "",
                ),
            )
            throw AssertionError("expected fail-closed on empty landing content")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("跨配置落地"))
        }
    }

    @Test
    fun displayHopTagStripsGeneratedPrefixes() {
        assertEquals("自动选择", ChainRuntimeCompiler.displayHopTag("chainbox-entry-自动选择"))
        assertEquals("zgo", ChainRuntimeCompiler.displayHopTag("chainbox-landing-99-zgo"))
        assertEquals("tracy-VLESS_TCP/TLS_WS", ChainRuntimeCompiler.displayHopTag("chainbox-landing-6-tracy-VLESS_TCP/TLS_WS"))
        assertEquals("", ChainRuntimeCompiler.displayHopTag("chainbox-chain-1-2"))
        assertEquals("节点选择", ChainRuntimeCompiler.displayHopTag("节点选择"))
    }

    @Test
    fun chainClonePreservesTlsEch() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "vless")
                            .put("tag", "cf")
                            .put("tls", JSONObject().put("enabled", true).put("ech", JSONObject().put("enabled", true).put("query_server_name", "cloudflare-ech.com"))),
                    )
                    .put(
                        JSONObject()
                            .put("type", "selector")
                            .put("tag", "节点选择")
                            .put("outbounds", JSONArray().put("cf")),
                    )
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put("route", JSONObject().put("final", "节点选择"))
        val landing = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "vless")
                            .put("tag", "us")
                            .put("tls", JSONObject().put("enabled", true).put("ech", JSONObject().put("enabled", true).put("query_server_name", "cloudflare-ech.com"))),
                    ),
            )
            .toString()
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = src.toString(),
                currentProfileId = 3L,
                entryTag = "节点选择",
                landingProfileId = 9L,
                landingTag = "us",
                landingContent = landing,
            ),
        )
        val outs = JSONObject(compiled).getJSONArray("outbounds")
        val landingOut = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("tag").startsWith("chainbox-landing-9-") }
        val ech = landingOut.getJSONObject("tls").getJSONObject("ech")
        assertEquals(true, ech.getBoolean("enabled"))
        assertEquals("cloudflare-ech.com", ech.getString("query_server_name"))
    }

    @Test
    fun crossProfileSameGroupNameDoesNotStripEntryMembers() {
        val entry = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("hk-1"))
                    .put(node("jp-1"))
                    .put(
                        JSONObject()
                            .put("type", "urltest")
                            .put("tag", "自动选择")
                            .put("outbounds", JSONArray().put("hk-1").put("jp-1")),
                    )
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put("route", JSONObject().put("final", "自动选择"))
            .toString()
        val landing = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("us-1"))
                    .put(
                        JSONObject()
                            .put("type", "urltest")
                            .put("tag", "自动选择")
                            .put("outbounds", JSONArray().put("us-1")),
                    ),
            )
            .toString()
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = entry,
                currentProfileId = 1L,
                entryTag = "自动选择",
                landingProfileId = 2L,
                landingTag = "自动选择",
                landingContent = landing,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        val hops = chain.getJSONArray("outbounds")
        assertEquals("自动选择", hops.getString(0))
        assertEquals("chainbox-landing-2-自动选择", hops.getString(1))
        val entryGroup = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("tag") == "自动选择" }
        val members = (0 until entryGroup.getJSONArray("outbounds").length()).map {
            entryGroup.getJSONArray("outbounds").getString(it)
        }
        assertTrue(members.contains("hk-1"))
        assertTrue(members.contains("jp-1"))
        assertEquals(root.getJSONObject("route").getString("final"), chain.optString("tag"))
        val pinned = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = JSONObject(entry).put(
                    "route",
                    JSONObject().put("final", "自动选择").put(
                        "rules",
                        JSONArray().put(JSONObject().put("outbound", "自动选择")),
                    ),
                ).toString(),
                currentProfileId = 1L,
                entryTag = "自动选择",
                landingProfileId = 2L,
                landingTag = "自动选择",
                landingContent = landing,
            ),
        )
        val pinnedRoot = JSONObject(pinned)
        val chainTag = pinnedRoot.getJSONObject("route").getString("final")
        val outbound = pinnedRoot.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound")
        assertEquals(chainTag, outbound)
        assertFalse(outbound == "自动选择")
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedConfigIsRejected() {
        val huge = "x".repeat(ChainRuntimeCompiler.MAX_CONFIG_CHARS + 8)
        ChainRuntimeCompiler.parseConfig(huge, "测试")
    }

    @Test
    fun mixedOrChinaAndGoogleIsNotBypass() {
        val rule = JSONObject()
            .put("rule_set", JSONArray().put("geoip-cn").put("geosite-google"))
            .put("outbound", "direct")
        assertFalse(ChainRuntimeCompiler.isBypassDirectRule(rule))
    }

    @Test
    fun chinaOnlyRuleSetIsBypass() {
        val rule = JSONObject().put("rule_set", JSONArray().put("geoip-cn")).put("outbound", "direct")
        assertTrue(ChainRuntimeCompiler.isBypassDirectRule(rule))
    }

    @Test
    fun haystackChinaAirlinesIsNotBypass() {
        val rule = JSONObject().put("domain", JSONArray().put("www.china-airlines.com")).put("outbound", "direct")
        assertFalse(ChainRuntimeCompiler.isBypassDirectRule(rule))
    }

    @Test
    fun privateCidrLiteralOnly() {
        assertTrue(ChainRuntimeCompiler.isPrivateCidr("10.0.0.0/8"))
        assertTrue(ChainRuntimeCompiler.isPrivateCidr("192.168.1.1/32"))
        assertFalse(ChainRuntimeCompiler.isPrivateCidr("1.1.1.1/32"))
        assertFalse(ChainRuntimeCompiler.isPrivateCidr("evil.example/32"))
        assertFalse(ChainRuntimeCompiler.isPrivateCidr("geoip-cn"))
    }

    @Test
    fun notCnRuleSetIsNotBypass() {
        val rule = JSONObject().put("rule_set", JSONArray().put("geosite-geolocation-!cn")).put("outbound", "direct")
        assertFalse(ChainRuntimeCompiler.isBypassDirectRule(rule))
    }
}
