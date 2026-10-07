package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChainRuntimeCompilerBacklogTest {

    private fun node(tag: String) = JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", "example.com")
        .put("server_port", 443)

    private fun selector(tag: String, vararg members: String) = JSONObject()
        .put("type", "selector")
        .put("tag", tag)
        .put("outbounds", JSONArray().apply { members.forEach { put(it) } })

    private fun wg(tag: String) = JSONObject()
        .put("type", "wireguard")
        .put("tag", tag)
        .put("address", JSONArray().put("172.16.0.2/32"))
        .put("private_key", "x")

    private fun members(root: JSONObject, tag: String): List<String> {
        val outs = root.getJSONArray("outbounds")
        for (i in 0 until outs.length()) {
            val o = outs.getJSONObject(i)
            if (o.optString("tag") == tag) {
                val m = o.getJSONArray("outbounds")
                return (0 until m.length()).map { m.getString(it) }
            }
        }
        error("no $tag")
    }

    private fun apply(content: String, entry: String, landing: String, landingProfile: Long = 1L, landingContent: String? = null) =
        JSONObject(
            ChainRuntimeCompiler.apply(
                ChainRuntimeCompiler.ApplyRequest(
                    content = content,
                    currentProfileId = 1L,
                    entryTag = entry,
                    landingProfileId = landingProfile,
                    landingTag = landing,
                    landingContent = landingContent,
                ),
            ),
        )

    @Test
    fun entryGroupKeepsWireGuardEndpointMember() {
        val content = JSONObject()
            .put("outbounds", JSONArray().put(node("us-1")).put(selector("入口", "warp", "hk-1")).put(node("hk-1")))
            .put("endpoints", JSONArray().put(wg("warp")))
            .put("route", JSONObject().put("final", "入口"))
            .toString()
        val root = apply(content, "入口", "us-1")
        assertEquals(listOf("warp", "hk-1"), members(root, "入口"))
    }

    @Test
    fun landingGroupDropsEndpointMemberInsteadOfFailing() {
        val landing = JSONObject()
            .put("outbounds", JSONArray().put(node("us-1")).put(selector("落地", "warp", "us-1")))
            .put("endpoints", JSONArray().put(wg("warp")))
            .toString()
        val content = JSONObject()
            .put("outbounds", JSONArray().put(node("hk-1")).put(selector("入口", "hk-1")))
            .put("route", JSONObject().put("final", "入口"))
            .toString()
        val root = apply(content, "入口", "落地", landingProfile = 2L, landingContent = landing)
        assertEquals(listOf("chainbox-landing-2-us-1"), members(root, "chainbox-landing-2-落地"))
    }

    @Test
    fun endpointAsLandingGivesClearError() {
        val content = JSONObject()
            .put("outbounds", JSONArray().put(node("hk-1")).put(selector("入口", "hk-1")))
            .put("endpoints", JSONArray().put(wg("warp")))
            .put("route", JSONObject().put("final", "入口"))
            .toString()
        try {
            apply(content, "入口", "warp")
            fail("expected error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("endpoint"))
        }
    }

    @Test
    fun sameProfileEntryDropsLandingNodes() {
        val content = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(node("us-1")).put(node("hk-1"))
                    .put(selector("美国", "us-1"))
                    .put(selector("入口", "us-1", "hk-1")),
            )
            .put("route", JSONObject().put("final", "入口"))
            .toString()
        val root = apply(content, "入口", "美国")
        assertEquals(listOf("hk-1"), members(root, "入口"))
        assertEquals(listOf("us-1"), members(root, "美国"))
    }

    @Test
    fun sameProfileEntryKeepsSharedNodesWhenNothingElseLeft() {
        val content = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(node("us-1")).put(node("us-2"))
                    .put(selector("美国", "us-1", "us-2"))
                    .put(selector("自动", "us-1", "us-2")),
            )
            .put("route", JSONObject().put("final", "自动"))
            .toString()
        val root = apply(content, "自动", "美国")
        assertEquals(listOf("us-1", "us-2"), members(root, "自动"))
        assertFalse(members(root, "自动").isEmpty())
    }
}
