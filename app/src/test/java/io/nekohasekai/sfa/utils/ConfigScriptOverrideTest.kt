package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertTrue(out.has("route"))
        assertTrue(out.getJSONObject("route").has("rule_set"))
        assertEquals("https", out.getJSONObject("dns").getJSONArray("servers").let { servers ->
            (0 until servers.length()).map { servers.getJSONObject(it) }
                .first { it.optString("tag") == "dns-remote" }
                .getString("type")
        })
        val mixed = out.getJSONArray("inbounds")
        assertTrue((0 until mixed.length()).any { mixed.getJSONObject(it).optString("type") == "mixed" })
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
        val firstAction = rules.getJSONObject(0).optString("action")
        assertEquals("hijack-dns", firstAction)
        assertTrue("quic drop", ruleText.contains("\"port\":443") || ruleText.contains("\"port\": 443"))
        val cnDns = out.getJSONObject("dns").getJSONArray("servers").let { servers ->
            (0 until servers.length()).map { servers.getJSONObject(it) }
                .first { it.optString("tag") == "dns-cn" }
        }
        assertEquals("direct", cnDns.optString("detour"))
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val dnsRuleText = (0 until dnsRules.length()).joinToString { dnsRules.getJSONObject(it).toString() }
        assertTrue(dnsRuleText.contains("65"))
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
}
