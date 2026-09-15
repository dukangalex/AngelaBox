package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRuntimeCompilerNestedGroupTest {
    private fun node(tag: String) = JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", "example.com")
        .put("server_port", 443)

    @Test
    fun nestedEntryGroupCannotReachDirect() {
        val config = JSONObject()
            .put("outbounds", JSONArray()
                .put(node("proxy-1"))
                .put(node("proxy-2"))
                .put(JSONObject().put("type", "selector").put("tag", "nested").put("outbounds", JSONArray().put("proxy-2").put("direct")))
                .put(JSONObject().put("type", "selector").put("tag", "entry").put("outbounds", JSONArray().put("nested")))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
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

    @Test(expected = IllegalArgumentException::class)
    fun nestedEntryCycleFailsClosed() {
        val config = JSONObject()
            .put("outbounds", JSONArray()
                .put(node("proxy-1"))
                .put(JSONObject().put("type", "selector").put("tag", "a").put("outbounds", JSONArray().put("b")))
                .put(JSONObject().put("type", "selector").put("tag", "b").put("outbounds", JSONArray().put("a")))
                .put(JSONObject().put("type", "selector").put("tag", "entry").put("outbounds", JSONArray().put("a")))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
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
