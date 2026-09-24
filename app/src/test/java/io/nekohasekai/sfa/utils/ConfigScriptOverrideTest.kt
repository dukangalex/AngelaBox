package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigScriptOverrideTest {

    @Test
    fun mainMutatesOutbounds() {
        val input = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(JSONObject().put("type", "shadowsocks").put("tag", "hk-1")),
            )
            .toString()
        val code = """
            function main(config) {
              config.log = { level: "info" };
              config.outbounds.push({ type: "direct", tag: "direct" });
              return config;
            }
        """.trimIndent()
        val out = JSONObject(ConfigScriptOverride.ScriptEngine.run(code, input, "t"))
        assertEquals("info", out.getJSONObject("log").getString("level"))
        val tags = (0 until out.getJSONArray("outbounds").length()).map {
            out.getJSONArray("outbounds").getJSONObject(it).getString("tag")
        }
        assertTrue(tags.contains("hk-1"))
        assertTrue(tags.contains("direct"))
    }

    @Test
    fun sampleCreatesRegionUrltest() {
        val file = File("src/main/assets/scripts/airport-region.js")
        if (!file.isFile) return
        val code = file.readText()
        val input = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "vless").put("tag", "香港 01"))
                    .put(JSONObject().put("type", "vmess").put("tag", "日本 Tokyo"))
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .toString()
        val out = JSONObject(ConfigScriptOverride.ScriptEngine.run(code, input, "sample"))
        val tags = (0 until out.getJSONArray("outbounds").length()).map {
            out.getJSONArray("outbounds").getJSONObject(it).optString("tag")
        }
        assertTrue(tags.any { it.contains("香港") })
        assertTrue(tags.any { it.contains("日本") })
        assertTrue(tags.any { it.contains("自动选择") || it.contains("节点选择") })
        assertTrue(tags.any { it.contains("🐟 漏网之鱼") })
        assertTrue(tags.any { it.contains("🔰 节点选择") || it.contains("♻️ 自动选择") })
        assertTrue(tags.contains("⚖️ 负载均衡"))
        assertTrue(tags.contains("🛡️ 故障转移"))
        assertTrue(out.has("route"))
        assertTrue(out.getJSONObject("route").has("rule_set"))
        assertEquals("tcp", out.getJSONObject("dns").getJSONArray("servers").let { servers ->
            (0 until servers.length()).map { servers.getJSONObject(it) }
                .first { it.optString("tag") == "dns-remote" }
                .getString("type")
        })
        val inbounds = out.getJSONArray("inbounds")
        val tun = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
            .first { it.optString("type") == "tun" }
        assertEquals("tun-in", tun.optString("tag"))
        assertEquals(true, tun.optBoolean("auto_route"))
        assertEquals(1500, tun.optInt("mtu"))
        assertFalse(tun.has("inet6_address"))
        assertFalse((0 until inbounds.length()).any { inbounds.getJSONObject(it).optString("type") == "mixed" })
        val remote = out.getJSONObject("dns").getJSONArray("servers").let { servers ->
            (0 until servers.length()).map { servers.getJSONObject(it) }
                .first { it.optString("tag") == "dns-remote" }
        }
        assertTrue(remote.optString("detour").isNotBlank())
        val rules = out.getJSONObject("route").getJSONArray("rules")
        val ruleText = (0 until rules.length()).joinToString("\n") { rules.getJSONObject(it).toString() }
        val geoip = ruleText.indexOf("geoip-cn")
        val notCn = ruleText.indexOf("geolocation-!cn")
        assertTrue(geoip >= 0 && (notCn < 0 || geoip < notCn))
        assertEquals(false, out.getJSONObject("route").optBoolean("find_process", true))
        assertTrue("hijack-dns", ruleText.contains("hijack-dns"))
        assertTrue(ruleText.contains("\"clash_mode\":\"Global\"") || ruleText.contains("\"clash_mode\": \"Global\""))
        assertTrue(ruleText.contains("\"clash_mode\":\"Direct\"") || ruleText.contains("\"clash_mode\": \"Direct\""))
        val firstAction = rules.getJSONObject(0).optString("action")
        assertEquals("hijack-dns", firstAction)
        assertFalse("udp 443 must stay open", ruleText.contains("\"port\":443") || ruleText.contains("\"port\": 443"))
        val cnDns = out.getJSONObject("dns").getJSONArray("servers").let { servers ->
            (0 until servers.length()).map { servers.getJSONObject(it) }
                .first { it.optString("tag") == "dns-cn" }
        }
        assertEquals("direct", cnDns.optString("detour"))
        assertEquals("udp", cnDns.optString("type"))
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val dnsRuleText = (0 until dnsRules.length()).joinToString { dnsRules.getJSONObject(it).toString() }
        assertTrue(dnsRuleText.contains("dns-fakeip"))
        assertFalse(dnsRuleText.contains("64, 65") || dnsRuleText.contains("[64"))
        val fake = (0 until out.getJSONObject("dns").getJSONArray("servers").length())
            .map { out.getJSONObject("dns").getJSONArray("servers").getJSONObject(it) }
            .first { it.optString("tag") == "dns-fakeip" }
        assertEquals("fakeip", fake.getString("type"))
        val direct = (0 until out.getJSONArray("outbounds").length())
            .map { out.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.optString("tag") == "direct" }
        assertEquals("8s", direct.getString("connect_timeout"))
    }

    @Test
    fun overlayScriptsRoundTrip() {
        val item = OverlayScript(
            id = "a",
            name = "demo",
            enabled = true,
            source = OverlayScripts.SOURCE_CODE,
            code = "function main(config) { return config; }",
        )
        val raw = OverlayScripts.encode(listOf(item))
        val decoded = OverlayScripts.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("demo", decoded[0].name)
        assertTrue(decoded[0].enabled)
        assertTrue(decoded[0].code.contains("function main"))
    }

    @Test
    fun scriptBindingsRoundTripAndInherit() {
        val encoded = OverlayScripts.encodeBindings(
            mapOf(
                12L to listOf("a", "b"),
                13L to emptyList(),
            ),
        )
        val decoded = OverlayScripts.decodeBindings(encoded)
        assertEquals(listOf("a", "b"), decoded[12L])
        assertEquals(emptyList<String>(), decoded[13L])
        assertEquals(true, OverlayScripts.decodeBindings("").isEmpty())
    }

    @Test
    fun runtimeJavaEscapeIsRejected() {
        val input = JSONObject().put("outbounds", JSONArray()).toString()
        val payloads = listOf(
            """function main(config) { java.lang.Runtime.getRuntime().exec("id"); return config; }""",
            """function main(config) { var R = Java.type("java.lang.Runtime"); return config; }""",
            """function main(config) { var x = Packages.java.lang.System; return config; }""",
        )
        payloads.forEach { code ->
            try {
                ConfigScriptOverride.ScriptEngine.run(code, input, "evil")
                throw AssertionError("expected sandbox reject for $code")
            } catch (e: Exception) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun infiniteLoopTimesOut() {
        val input = JSONObject().put("outbounds", JSONArray()).toString()
        val code = """function main(config) { while (true) {} }"""
        val started = System.currentTimeMillis()
        try {
            ConfigScriptOverride.ScriptEngine.run(code, input, "loop")
            throw AssertionError("expected timeout")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("超时") || e.message.orEmpty().isNotBlank())
        }
        assertTrue(System.currentTimeMillis() - started < 20_000)
    }

    @Test
    fun overlaySwitchTurnsOffAdsAndChina() {
        val file = File("src/main/assets/scripts/airport-region.js")
        if (!file.isFile) return
        val code = file.readText()
        val input = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "vless").put("tag", "香港 01"))
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .toString()
        val off = JSONObject()
            .put("chinaDirect", false)
            .put("adsBlock", false)
            .put("webrtcProtect", false)
            .put("disableQuic", false)
            .put("excludeCnQuic", false)
            .put("disableIpv6", false)
            .put("dnsProtect", false)
            .put("strictRoute", false)
            .toString()
        val out = JSONObject(ConfigScriptOverride.ScriptEngine.run(code, input, "sample-off", off))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        val ruleText = (0 until rules.length()).joinToString("\n") { rules.getJSONObject(it).toString() }
        assertTrue("hijack-dns stays", ruleText.contains("hijack-dns"))
        assertTrue("ads off", !ruleText.contains("geosite-category-ads-all"))
        assertTrue("stun off", !ruleText.contains("3478:3481"))
        assertTrue("quic off", !ruleText.contains("\"port\":443") && !ruleText.contains("\"port\": 443"))
        assertEquals("prefer_ipv4", out.getJSONObject("dns").optString("strategy"))
        assertTrue(out.getJSONObject("dns").toString().contains("2400:3200::1"))
        assertTrue(out.getJSONObject("dns").toString().contains("2001:4860:4860::8888"))
    }

    @Test
    fun overlayDefaultKeepsProtectiveRules() {
        val file = File("src/main/assets/scripts/airport-region.js")
        if (!file.isFile) return
        val code = file.readText()
        val input = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "vless").put("tag", "日本 Tokyo"))
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .toString()
        val out = JSONObject(ConfigScriptOverride.ScriptEngine.run(code, input, "sample-on"))
        val rules = out.getJSONObject("route").getJSONArray("rules")
        val ruleText = (0 until rules.length()).joinToString("\n") { rules.getJSONObject(it).toString() }
        assertTrue(ruleText.contains("geoip-cn"))
        assertTrue(ruleText.contains("geosite-category-ads-all"))
        assertTrue(ruleText.contains("3478:3481"))
        assertFalse(ruleText.contains("\"port\":443") || ruleText.contains("\"port\": 443"))
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val dnsRuleText = (0 until dnsRules.length()).joinToString { dnsRules.getJSONObject(it).toString() }
        assertFalse(dnsRuleText.contains("query_type"))
        assertTrue(ruleText.contains("youtubei.googleapis.com"))
        assertEquals("ipv4_only", out.getJSONObject("dns").optString("strategy"))
    }

    @Test
    fun sampleStripsLeafChainAndKeepsDirectException() {
        val file = File("src/main/assets/scripts/airport-region.js")
        if (!file.isFile) return
        val code = file.readText()
        val input = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "vless")
                            .put("tag", "香港 01")
                            .put("detour", "proxy-select")
                            .put("dialer-proxy", "chain-in"),
                    )
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .toString()
        val out = JSONObject(ConfigScriptOverride.ScriptEngine.run(code, input, "sample-sanitizer"))
        val outs = out.getJSONArray("outbounds")
        val byTag = (0 until outs.length()).associate {
            val item = outs.getJSONObject(it)
            item.optString("tag") to item
        }
        val leaf = byTag.getValue("香港 01")
        assertTrue(leaf.optString("detour").isEmpty())
        assertTrue(!leaf.has("dialer-proxy"))
        val ads = byTag.getValue("🛑 广告拦截")
        val adsMembers = (0 until ads.getJSONArray("outbounds").length()).map {
            ads.getJSONArray("outbounds").getString(it)
        }
        assertTrue(adsMembers.contains("direct"))
        val select = byTag.entries.first { it.key.contains("节点选择") }.value
        val selectMembers = (0 until select.getJSONArray("outbounds").length()).map {
            select.getJSONArray("outbounds").getString(it)
        }
        assertTrue(selectMembers.none { it.equals("direct", ignoreCase = true) })
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val dnsText = (0 until dnsRules.length()).joinToString { dnsRules.getJSONObject(it).toString() }
        assertTrue(dnsText.contains("aistudio.google.com"))
        assertTrue(dnsText.contains("dns-remote"))
    }

    @Test
    fun independentAirportTunCreatesTunAndHasNoChain() {
        val file = File("../docs/scripts/airport-tun.js")
        if (!file.isFile) return
        val code = file.readText()
        assertTrue(code.contains("airport-tun-revision: 3"))
        assertTrue(!code.contains("function find(") && !code.contains(".find("))
        assertTrue(!code.contains("?.") && !code.contains("..."))
        val input = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "vless")
                            .put("tag", "香港 01")
                            .put("detour", "proxy-select")
                            .put("dialer-proxy", "chain-in"),
                    )
                    .put(JSONObject().put("type", "vmess").put("tag", "美国 Los Angeles"))
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .toString()
        val out = JSONObject(ConfigScriptOverride.ScriptEngine.run(code, input, "airport-tun"))
        val tags = (0 until out.getJSONArray("outbounds").length()).map {
            out.getJSONArray("outbounds").getJSONObject(it).optString("tag")
        }
        assertTrue(tags.any { it.contains("香港") })
        assertTrue(tags.any { it.contains("美国") })
        assertTrue(tags.contains("♻️ 自动选择"))
        assertTrue(tags.contains("⚖️ 负载均衡"))
        assertTrue(tags.contains("🛡️ 故障转移"))
        assertTrue(tags.contains("🤖 Claude AI"))
        assertTrue(tags.contains("🔰 节点选择"))
        val inbounds = out.getJSONArray("inbounds")
        val tun = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
            .first { it.optString("type") == "tun" }
        assertEquals("tun-in", tun.optString("tag"))
        assertEquals(true, tun.optBoolean("auto_route"))
        assertEquals(true, tun.optBoolean("strict_route"))
        assertEquals(true, tun.optBoolean("sniff"))
        assertTrue(!tun.has("stack"))
        val mixed = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
            .first { it.optString("type") == "mixed" }
        assertEquals("127.0.0.1", mixed.optString("listen"))
        assertEquals(17890, mixed.optInt("listen_port"))
        val byTag = (0 until out.getJSONArray("outbounds").length()).associate {
            val item = out.getJSONArray("outbounds").getJSONObject(it)
            item.optString("tag") to item
        }
        val leaf = byTag.getValue("香港 01")
        assertTrue(leaf.optString("detour").isEmpty())
        assertTrue(!leaf.has("dialer-proxy"))
        assertTrue(!out.has("proxies"))
        assertTrue(!out.has("proxy-groups"))
    }
}
