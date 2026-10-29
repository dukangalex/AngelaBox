package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * NF-8 guardrails: group-expansion depth and total materialized
 * outbounds are hard limits (fail-closed, never truncated), and members
 * discarded during group preparation are reported through
 * [ChainRuntimeCompiler.lastDiscarded] instead of vanishing silently.
 *
 * There is deliberately NO cap on the two hops' leaf count. The kernel
 * compiler (option/chain_compile.go) expands ONLY the landing hop — one
 * clone per distinct landing leaf plus one synthetic group — and never
 * touches the entry hop, so the final outbound count grows linearly:
 * appOuts + landingLeaves + 1. A leaf sum is not a blowup mode; the old
 * 64-leaf cap rejected a real airport profile whose entry group and
 * landing group together held 70 leaves (v1.0.133-beta field failure).
 * Runaway materialization is bounded by MAX_MATERIALIZED_OUTBOUNDS and
 * by the input size cap instead.
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

    private fun applySameProfile(content: String, entry: String, landing: String): String = ChainRuntimeCompiler.apply(
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
    fun seventyLeafAirportShapeCompiles() {
        // The v1.0.133-beta field failure, modelled on the user's
        // profile: main selector -> HK region group (12 leaves) plus an
        // auto-select landing group (58 leaves). Distinct leaves across
        // the two hops = 12 + 58 = 70, which the old MAX_CHAIN_LEAVES
        // (64) rejected with 「叶子节点过多」 — and the minimal-config
        // fallback hit the same cap, so the service could not start.
        val outs = JSONArray()
        val hk = JSONArray()
        for (i in 1..12) {
            outs.put(node("hk-$i"))
            hk.put("hk-$i")
        }
        val landing = JSONArray()
        for (i in 1..58) {
            outs.put(node("land-$i"))
            landing.put("land-$i")
        }
        outs.put(
            JSONObject().put("type", "urltest").put("tag", "香港").put("outbounds", hk),
        )
        outs.put(
            JSONObject().put("type", "urltest").put("tag", "自动选择").put("outbounds", landing),
        )
        outs.put(group("节点选择", "香港", "自动选择"))
        val content = JSONObject()
            .put("outbounds", outs)
            .put("route", JSONObject().put("final", "节点选择"))
            .toString()
        val compiled = applySameProfile(content, entry = "节点选择", landing = "自动选择")
        val root = JSONObject(compiled)
        val chainTag = ChainRuntimeCompiler.chainTag(1L, 1L)
        assertEquals(chainTag, root.getJSONObject("route").getString("final"))
        val chain = root.getJSONArray("outbounds").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("tag") == chainTag }
        }
        assertEquals("节点选择", chain.getJSONArray("outbounds").getString(0))
        assertEquals("自动选择", chain.getJSONArray("outbounds").getString(1))
        // App-side total: 70 leaves + 3 groups + 1 chain. The kernel adds
        // one clone per landing leaf (58) plus one synthetic group, so
        // the running config stays ~133 outbounds — linear, nowhere near
        // MAX_MATERIALIZED_OUTBOUNDS.
        assertEquals(74, root.getJSONArray("outbounds").length())
    }

    @Test
    fun twoHundredLeafStressShapeCompiles() {
        // Wide margin over the field shape: two nested region groups on
        // the entry hop (60 + 60 leaves) and an 80-leaf landing group —
        // 200 distinct leaves across the two hops.
        val outs = JSONArray()
        val regionA = JSONArray()
        val regionB = JSONArray()
        for (i in 1..60) {
            outs.put(node("a-$i"))
            regionA.put("a-$i")
            outs.put(node("b-$i"))
            regionB.put("b-$i")
        }
        val landing = JSONArray()
        for (i in 1..80) {
            outs.put(node("exit-$i"))
            landing.put("exit-$i")
        }
        outs.put(JSONObject().put("type", "urltest").put("tag", "区域A").put("outbounds", regionA))
        outs.put(JSONObject().put("type", "urltest").put("tag", "区域B").put("outbounds", regionB))
        outs.put(JSONObject().put("type", "urltest").put("tag", "自动选择").put("outbounds", landing))
        outs.put(group("节点选择", "区域A", "区域B", "自动选择"))
        val content = JSONObject()
            .put("outbounds", outs)
            .put("route", JSONObject().put("final", "节点选择"))
            .toString()
        val compiled = applySameProfile(content, entry = "节点选择", landing = "自动选择")
        val root = JSONObject(compiled)
        assertEquals(
            ChainRuntimeCompiler.chainTag(1L, 1L),
            root.getJSONObject("route").getString("final"),
        )
        // 200 leaves + 4 groups + 1 chain; the kernel would add 80
        // landing clones + 1 synthetic group on top. Still linear.
        assertEquals(205, root.getJSONArray("outbounds").length())
    }

    @Test
    fun sixHundredMemberGroupCompiles() {
        // Adjacent to the v1.0.133-beta leaf incident: a mega-airport
        // "all nodes" group legitimately holds more than the old
        // MAX_GROUP_MEMBERS (512). Member count is a list length, not a
        // blowup mode, so a 600-member entry group must compile.
        val outs = JSONArray()
        val all = JSONArray()
        for (i in 1..600) {
            outs.put(node("n-$i"))
            all.put("n-$i")
        }
        val landing = JSONArray()
        for (i in 1..3) {
            outs.put(node("exit-$i"))
            landing.put("exit-$i")
        }
        outs.put(JSONObject().put("type", "urltest").put("tag", "全部节点").put("outbounds", all))
        outs.put(JSONObject().put("type", "urltest").put("tag", "自动选择").put("outbounds", landing))
        outs.put(group("节点选择", "全部节点", "自动选择"))
        val content = JSONObject()
            .put("outbounds", outs)
            .put("route", JSONObject().put("final", "节点选择"))
            .toString()
        val compiled = applySameProfile(content, entry = "节点选择", landing = "自动选择")
        val root = JSONObject(compiled)
        assertEquals(
            ChainRuntimeCompiler.chainTag(1L, 1L),
            root.getJSONObject("route").getString("final"),
        )
        // 603 leaves + 3 groups + 1 chain.
        assertEquals(607, root.getJSONArray("outbounds").length())
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
