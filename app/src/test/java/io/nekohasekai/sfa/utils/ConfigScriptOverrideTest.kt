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
        assertTrue(tags.any { it.contains("自动选择") })
        assertTrue(tags.contains("漏网之鱼"))
        assertTrue(tags.contains("默认代理"))
        assertFalse(tags.contains("⚖️ 负载均衡"))
        assertFalse(tags.contains("🛡️ 故障转移"))
        val us = (0 until out.getJSONArray("outbounds").length())
            .map { out.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.optString("tag") == "🇭🇰 香港" }
        val usMembers = (0 until us.getJSONArray("outbounds").length()).map {
            us.getJSONArray("outbounds").getString(it)
        }
        assertTrue(usMembers.contains("🇭🇰 香港-自动选择"))
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
        assertTrue("udp 443 reject resets", ruleText.contains("\"port\":443") || ruleText.contains("\"port\": 443"))
        assertFalse(Regex(""""port"\s*:\s*443[\s\S]{0,40}"method"\s*:\s*"drop"""").containsMatchIn(ruleText))
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
    fun sampleClearsStrictRouteWhenSwitchOff() {
        val file = File("src/main/assets/scripts/airport-region.js")
        if (!file.isFile) return
        val input = JSONObject()
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("strict_route", true)
                        .put("address", "172.19.0.1/30"),
                ),
            )
            .put(
                "outbounds",
                JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .toString()
        val overlay = ConfigScriptOverride.defaultOverlayFlags().put("strictRoute", false).toString()
        val out = JSONObject(
            ConfigScriptOverride.ScriptEngine.run(file.readText(), input, "sample", overlay),
        )
        val inbounds = out.getJSONArray("inbounds")
        val tun = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
            .first { it.optString("type") == "tun" }
        assertFalse(tun.getBoolean("strict_route"))
    }

    @Test
    fun retiredAirportLeavesTheLibrary() {
        val airport = OverlayScript(
            id = "old",
            name = "机场覆写",
            enabled = false,
            source = "airport",
            code = "function main(config) { return config; }",
        )
        val pasted = airport.copy(id = "paste", source = OverlayScripts.SOURCE_CODE)
        val keep = OverlayScript(
            id = "keep",
            name = "我的脚本",
            enabled = true,
            source = OverlayScripts.SOURCE_FILE,
            code = "function main(config) { return config; }",
        )
        val kept = OverlayScripts.withoutRetiredAirport(listOf(airport, pasted, keep))
        assertEquals(listOf("keep"), kept.map { it.id })
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
        assertTrue("stun off", !ruleText.contains("3478:3497"))
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
        assertTrue(ruleText.contains("3478:3497"))
        assertTrue("quic reject resets instead of dropping", ruleText.contains("\"port\":443") || ruleText.contains("\"port\": 443"))
        assertFalse(Regex(""""port"\s*:\s*443[\s\S]{0,40}"method"\s*:\s*"drop"""").containsMatchIn(ruleText))
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val dnsRuleText = (0 until dnsRules.length()).joinToString { dnsRules.getJSONObject(it).toString() }
        assertTrue(dnsRuleText.contains("dns-fakeip"))
        assertTrue(dnsRuleText.contains("A"))
        assertTrue(ruleText.contains("youtubei.googleapis.com"))
        assertTrue(ruleText.contains("googlevideo.com"))
        assertTrue(ruleText.contains("com.google.android.youtube"))
        assertTrue(dnsRuleText.contains("HTTPS"))
        assertTrue(dnsRuleText.contains("SVCB"))
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
        val remoteTools = byTag.getValue("远控工具")
        val remoteMembers = (0 until remoteTools.getJSONArray("outbounds").length()).map {
            remoteTools.getJSONArray("outbounds").getString(it)
        }
        assertTrue(remoteMembers.contains("直连"))
        val select = byTag.getValue("默认代理")
        val selectMembers = (0 until select.getJSONArray("outbounds").length()).map {
            select.getJSONArray("outbounds").getString(it)
        }
        assertTrue(selectMembers.none { it.equals("direct", ignoreCase = true) })
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val dnsText = (0 until dnsRules.length()).joinToString { dnsRules.getJSONObject(it).toString() }
        val routeRules = out.getJSONObject("route").getJSONArray("rules")
        val routeText = (0 until routeRules.length()).joinToString { routeRules.getJSONObject(it).toString() }
        assertTrue(routeText.contains("aistudio.google.com"))
        assertTrue(dnsText.contains("dns-fakeip"))
        assertEquals("dns-remote", out.getJSONObject("dns").optString("final"))
    }

    private fun runSample(outbounds: JSONArray, endpoints: JSONArray? = null): JSONObject? {
        val file = File("src/main/assets/scripts/airport-region.js")
        if (!file.isFile) return null
        val input = JSONObject().put("outbounds", outbounds)
        if (endpoints != null) input.put("endpoints", endpoints)
        return JSONObject(ConfigScriptOverride.ScriptEngine.run(file.readText(), input.toString(), "sample"))
    }

    private fun byTag(out: JSONObject): Map<String, JSONObject> {
        val all = mutableListOf<JSONObject>()
        val outs = out.getJSONArray("outbounds")
        for (i in 0 until outs.length()) all += outs.getJSONObject(i)
        val eps = out.optJSONArray("endpoints")
        if (eps != null) for (i in 0 until eps.length()) all += eps.getJSONObject(i)
        val tags = all.map { it.optString("tag") }
        assertEquals("duplicate tags: $tags", tags.size, tags.toSet().size)
        return all.associateBy { it.optString("tag") }
    }

    private fun members(group: JSONObject?): List<String> {
        val arr = group?.optJSONArray("outbounds") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun leaf(tag: String) = JSONObject().put("type", "vless").put("tag", tag).put("server", "a.example")

    @Test
    fun sampleFilesEachNodeIntoOneRegion() {
        val names = listOf(
            "HK01", "HK_01", "🇯🇵 东京 02", "US-LA", "罗马尼亚 01", "Romania-1", "马里兰 01",
            "Mali 1", "SS-Node", "CF-01", "LA 01", "内蒙古 01", "Britain 01", "Abu Dhabi",
        )
        val outs = JSONArray()
        names.forEach { outs.put(leaf(it)) }
        outs.put(JSONObject().put("type", "direct").put("tag", "direct"))
        val out = runSample(outs) ?: return
        val tags = byTag(out)
        assertEquals(listOf("HK01", "HK_01", "🇭🇰 香港-自动选择"), members(tags["🇭🇰 香港"]))
        assertEquals("🇭🇰 香港-自动选择", tags.getValue("🇭🇰 香港").optString("default"))
        assertTrue(members(tags["🇯🇵 日本"]).contains("🇯🇵 东京 02"))
        assertEquals(listOf("US-LA", "马里兰 01"), members(tags["🇺🇸 美国-自动选择"]))
        assertEquals(listOf("罗马尼亚 01", "Romania-1"), members(tags["🇷🇴 罗马尼亚-自动选择"]))
        assertEquals(listOf("Mali 1"), members(tags["🇲🇱 马里-自动选择"]))
        assertEquals(listOf("Britain 01"), members(tags["🇬🇧 英国-自动选择"]))
        assertEquals(listOf("Abu Dhabi"), members(tags["🇦🇪 阿联酋-自动选择"]))
        assertEquals(
            listOf("SS-Node", "CF-01", "LA 01", "内蒙古 01"),
            members(tags["其他节点-自动选择"]),
        )
        listOf("🇸🇸 南苏丹", "🇨🇫 中非共和国", "🇱🇦 老挝", "🇴🇲 阿曼", "🇮🇹 意大利", "🇲🇳 蒙古").forEach {
            assertFalse("$it should not exist", tags.containsKey(it))
        }
        // Every group member and rule outbound points at a real tag.
        tags.values.forEach { ob ->
            members(ob).forEach { m -> assertTrue("${ob.optString("tag")} -> $m", tags.containsKey(m)) }
        }
        val rules = out.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val o = rules.getJSONObject(i).optString("outbound")
            if (o.isNotEmpty()) assertTrue("rule -> $o", tags.containsKey(o))
        }
    }

    @Test
    fun sampleDropsInfoNodesAndSplitsRates() {
        val outs = JSONArray()
            .put(leaf("剩余流量：100G"))
            .put(leaf("官网 example"))
            .put(leaf("交流群 123"))
            .put(leaf("香港 支持Netflix 更新"))
            .put(leaf("0.2倍 香港"))
            .put(leaf("10.5 日本"))
            .put(leaf("日本 x2"))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val out = runSample(outs) ?: return
        val tags = byTag(out)
        assertFalse(tags.containsKey("剩余流量：100G"))
        assertFalse(tags.containsKey("官网 example"))
        assertFalse(tags.containsKey("交流群 123"))
        assertTrue(tags.containsKey("香港 支持Netflix 更新"))
        assertEquals(listOf("0.2倍 香港"), members(tags["低倍率节点"]))
        assertEquals(listOf("日本 x2"), members(tags["高倍率节点"]))
    }

    @Test
    fun sampleRenamesDuplicateAndReservedTags() {
        val outs = JSONArray()
            .put(leaf("香港 01"))
            .put(leaf("香港 01"))
            .put(leaf("🇭🇰 香港"))
            .put(leaf("默认代理"))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val out = runSample(outs) ?: return
        val tags = byTag(out)
        assertTrue(tags.containsKey("香港 01 (2)"))
        assertTrue(tags.containsKey("🇭🇰 香港 (2)"))
        assertTrue(tags.containsKey("默认代理 (2)"))
        assertEquals("selector", tags.getValue("默认代理").optString("type"))
        assertEquals("selector", tags.getValue("🇭🇰 香港").optString("type"))
    }

    @Test
    fun sampleUsesDomainResolverAndGroupsWireGuardEndpoints() {
        val outs = JSONArray()
            .put(leaf("香港 01").put("domain_strategy", "prefer_ipv4"))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val eps = JSONArray()
            .put(JSONObject().put("type", "wireguard").put("tag", "WG 日本 01"))
            .put(JSONObject().put("type", "tailscale").put("tag", "ts"))
        val out = runSample(outs, eps) ?: return
        val tags = byTag(out)
        val hk = tags.getValue("香港 01")
        assertFalse(hk.has("domain_strategy"))
        assertEquals("dns-cn", hk.getJSONObject("domain_resolver").getString("server"))
        assertEquals("prefer_ipv4", hk.getJSONObject("domain_resolver").getString("strategy"))
        val text = out.getJSONArray("outbounds").toString()
        assertFalse(text.contains("domain_strategy"))
        assertTrue(members(tags["🇯🇵 日本-自动选择"]).contains("WG 日本 01"))
        assertFalse(members(tags["自动选择"]).contains("ts"))
        assertEquals(2, out.getJSONArray("endpoints").length())
        val dnsTags = out.getJSONObject("dns").getJSONArray("servers").let { s ->
            (0 until s.length()).map { s.getJSONObject(it).optString("tag") }
        }
        assertTrue(dnsTags.contains("dns-cn"))
    }

    @Test
    fun sampleHandlesTwelveHundredNodesQuickly() {
        val pool = listOf("香港", "日本", "US", "SG", "🇹🇼", "Romania", "Node")
        val outs = JSONArray()
        for (i in 0 until 1200) outs.put(leaf("${pool[i % pool.size]} ${i % 40}"))
        outs.put(JSONObject().put("type", "direct").put("tag", "direct"))
        val started = System.currentTimeMillis()
        val out = runSample(outs) ?: return
        val took = System.currentTimeMillis() - started
        val tags = byTag(out)
        assertEquals(1200, members(tags["手动选择"]).size)
        assertTrue("took $took ms", took < 4_000)
    }
}
