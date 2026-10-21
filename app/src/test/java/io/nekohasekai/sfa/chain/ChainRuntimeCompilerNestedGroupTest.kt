package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRuntimeCompilerNestedGroupTest {
    private fun node(tag: String) = JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", "example.com")
        .put("server_port", 443)

    private fun group(tag: String, vararg members: String, default: String? = null): JSONObject {
        val g = JSONObject().put("type", "selector").put("tag", tag)
            .put("outbounds", JSONArray().apply { members.forEach { put(it) } })
        if (default != null) g.put("default", default)
        return g
    }

    private fun outbound(root: JSONObject, tag: String): JSONObject {
        val outs = root.getJSONArray("outbounds")
        return (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag") == tag }
    }

    private fun membersOf(root: JSONObject, tag: String): List<String> {
        val arr = outbound(root, tag).getJSONArray("outbounds")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun compile(config: JSONObject, entry: String, landing: String): JSONObject {
        return JSONObject(
            ChainRuntimeCompiler.apply(
                ChainRuntimeCompiler.ApplyRequest(
                    content = config.toString(),
                    currentProfileId = 1L,
                    entryTag = entry,
                    landingProfileId = 1L,
                    landingTag = landing,
                    landingContent = null,
                ),
            ),
        )
    }

    @Test
    fun nestedEntryGroupCannotReachDirect() {
        val config = JSONObject()
            .put("outbounds", JSONArray()
                .put(node("proxy-1"))
                .put(node("proxy-2"))
                .put(JSONObject().put("type", "selector").put("tag", "nested").put("outbounds", JSONArray().put("proxy-2").put("direct")))
                .put(JSONObject().put("type", "selector").put("tag", "entry").put("outbounds", JSONArray().put("nested")))
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "entry"))

        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = config.toString(),
                currentProfileId = 1L,
                entryTag = "entry",
                landingProfileId = 1L,
                landingTag = "proxy-1",
                landingContent = null,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val entry = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag") == "entry" }
        val nestedTag = entry.getJSONArray("outbounds").getString(0)
        val nested = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag") == nestedTag }
        val members = (0 until nested.getJSONArray("outbounds").length()).map { nested.getJSONArray("outbounds").getString(it) }
        assertFalse(members.contains("direct"))
        assertTrue(members.contains("proxy-2"))
    }

    @Test
    fun nestedEntryGroupDropsLandingLeavesAtEveryDepth() {
        // Entry 主组 → 地区组 → landing leaf us-1: the leaf must be excluded
        // inside the nested group too, or the chain can loop onto itself.
        val config = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("us-1"))
                    .put(node("hk-1"))
                    .put(node("jp-1"))
                    .put(group("落地", "us-1"))
                    .put(group("地区", "us-1", "hk-1", default = "us-1"))
                    .put(group("主组", "地区", "jp-1")),
            )
            .put("route", JSONObject().put("final", "主组"))

        val root = compile(config, "主组", "落地")
        assertEquals(listOf("hk-1"), membersOf(root, "地区"))
        assertFalse(outbound(root, "地区").has("default"))
        assertEquals(listOf("地区", "jp-1"), membersOf(root, "主组"))
        assertEquals(listOf("us-1"), membersOf(root, "落地"))
    }

    @Test
    fun nestedGroupEmptiedByExclusionFallsBackInsteadOfFailing() {
        // The only nested group holds nothing but the landing leaf: the
        // exclusion attempt empties the entry, so the compiler falls back
        // to the unfiltered graph instead of failing the start.
        val config = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("us-1"))
                    .put(group("落地", "us-1"))
                    .put(group("地区", "us-1"))
                    .put(group("主组", "地区")),
            )
            .put("route", JSONObject().put("final", "主组"))

        val root = compile(config, "主组", "落地")
        assertEquals(listOf("地区"), membersOf(root, "主组"))
        assertEquals(listOf("us-1"), membersOf(root, "地区"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nestedEntryCycleFailsClosed() {
        val config = JSONObject()
            .put("outbounds", JSONArray()
                .put(node("proxy-1"))
                .put(JSONObject().put("type", "selector").put("tag", "a").put("outbounds", JSONArray().put("b")))
                .put(JSONObject().put("type", "selector").put("tag", "b").put("outbounds", JSONArray().put("a")))
                .put(JSONObject().put("type", "selector").put("tag", "entry").put("outbounds", JSONArray().put("a")))
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "entry"))

        ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = config.toString(),
                currentProfileId = 1L,
                entryTag = "entry",
                landingProfileId = 1L,
                landingTag = "proxy-1",
                landingContent = null,
            ),
        )
    }
}
