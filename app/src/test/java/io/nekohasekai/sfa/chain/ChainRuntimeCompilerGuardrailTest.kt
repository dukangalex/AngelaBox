package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * NF-8 guardrails: group-expansion depth, total chain leaves and total
 * materialized outbounds are hard limits (fail-closed, never truncated),
 * and members discarded during group preparation are reported through
 * [ChainRuntimeCompiler.lastDiscarded] instead of vanishing silently.
 */
class ChainRuntimeCompilerGuardrailTest {

    private fun node(tag: String) = JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", "example.com")
        .put("server_port", 443)

    private fun group(tag: String, vararg members: String) = JSONObject()
        .put("type", "selector")
        .put("tag", tag)
        .put("outbounds", JSONArray().also { arr -> members.forEach { arr.put(it) } })

    private fun profileOf(outbounds: JSONArray): String = JSONObject()
        .put("outbounds", outbounds)
        .put("route", JSONObject().put("final", "g0"))
        .toString()

    private fun applySameProfile(content: String, entry: String, landing: String): String =
        ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = content,
                currentProfileId = 1L,
                entryTag = entry,
                landingProfileId = 1L,
                landingTag = landing,
                landingContent = null,
            ),
        )

    @Test
    fun expansionDepthBeyondCapFailsClosed() {
        // g0 -> g1 -> g2 -> g3 -> g4 -> g5 -> leaf: the g5 expansion sits
        // at depth 5, past MAX_GROUP_EXPANSION_DEPTH (4).
        val outs = JSONArray()
            .put(node("leaf"))
            .put(node("land-1"))
            .put(group("g5", "leaf"))
            .put(group("g4", "g5"))
            .put(group("g3", "g4"))
            .put(group("g2", "g3"))
            .put(group("g1", "g2"))
            .put(group("g0", "g1"))
        try {
            applySameProfile(profileOf(outs), entry = "g0", landing = "land-1")
            fail("expected the depth cap to reject the chain")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("嵌套过深"))
        }
    }

    @Test
    fun leafCountBeyondCapFailsClosed() {
        val members = JSONArray()
        val outs = JSONArray()
        for (i in 1..(ChainRuntimeCompiler.MAX_CHAIN_LEAVES + 1)) {
            outs.put(node("n$i"))
            members.put("n$i")
        }
        outs.put(node("land-1"))
        outs.put(
            JSONObject().put("type", "selector").put("tag", "g0").put("outbounds", members),
        )
        try {
            applySameProfile(profileOf(outs), entry = "g0", landing = "land-1")
            fail("expected the leaf cap to reject the chain")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("叶子节点过多"))
        }
    }

    @Test
    fun totalOutboundsBeyondCapFailsClosed() {
        val outs = JSONArray()
        for (i in 1..(ChainRuntimeCompiler.MAX_MATERIALIZED_OUTBOUNDS + 8)) {
            outs.put(node("n$i"))
        }
        outs.put(group("g0", "n1"))
        try {
            applySameProfile(profileOf(outs), entry = "g0", landing = "n2")
            fail("expected the outbound cap to reject the chain")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("outbound 总数过多"))
        }
    }

    @Test
    fun withinCapsCompilesAndReportsNothing() {
        val outs = JSONArray()
            .put(node("hk-1"))
            .put(node("jp-1"))
            .put(group("g0", "hk-1"))
        val compiled = applySameProfile(profileOf(outs), entry = "g0", landing = "jp-1")
        assertTrue(JSONObject(compiled).getJSONObject("route").getString("final").startsWith("chainbox-chain-"))
        assertTrue(ChainRuntimeCompiler.lastDiscarded.isEmpty())
    }

    @Test
    fun discardedEntryMembersAreReported() {
        // The entry group mixes a valid node with reserved-name members;
        // the reserved ones are dropped from the chain and must surface.
        val outs = JSONArray()
            .put(node("hk-1"))
            .put(node("jp-1"))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "block").put("tag", "block"))
            .put(group("g0", "hk-1", "direct", "block"))
        applySameProfile(profileOf(outs), entry = "g0", landing = "jp-1")
        val discarded = ChainRuntimeCompiler.lastDiscarded
        assertEquals(
            setOf("direct", "block"),
            discarded.filter { it.groupTag == "g0" }.map { it.memberTag }.toSet(),
        )
        assertTrue(discarded.all { it.reason.isNotBlank() })
    }

    @Test
    fun discardedLandingMembersAreReported() {
        val landingOuts = JSONArray()
            .put(node("land-1"))
            .put(JSONObject().put("type", "dns").put("tag", "dns-out"))
            .put(group("lg", "land-1", "dns-out"))
        val entryOuts = JSONArray()
            .put(node("hk-1"))
            .put(group("g0", "hk-1"))
        ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = profileOf(entryOuts),
                currentProfileId = 1L,
                entryTag = "g0",
                landingProfileId = 2L,
                landingTag = "lg",
                landingContent = profileOf(landingOuts),
            ),
        )
        val discarded = ChainRuntimeCompiler.lastDiscarded
        assertTrue(
            discarded.any { it.groupTag == "lg" && it.memberTag == "dns-out" && it.reason.contains("dns") },
        )
    }
}
