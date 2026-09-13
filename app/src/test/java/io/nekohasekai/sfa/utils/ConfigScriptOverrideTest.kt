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
}
