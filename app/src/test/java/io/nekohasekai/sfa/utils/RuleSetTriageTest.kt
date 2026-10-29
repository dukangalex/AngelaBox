package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-set rule-set triage (v1.0.136). Field shape from the
 * v1.0.135-beta run on the user's phone: the service started, but
 * ONE undownloadable script-generated set (`geosite-nvidia@cn` on a
 * CDN path his phone could not fetch directly) failed the kernel
 * attempt and the ladder then skipped EVERY remote rule-set —
 * subscription sets and the app-injected CN/ads sets alike,
 * reachable or not. The user's requirement: a subscription's sets
 * are a mix — keep the usable ones, skip only the unusable ones.
 *
 * These tests drive the same pure pieces BoxService composes
 * (RuleSetTriage.plan/probeAll/decide +
 * ConfigInboundCompat.dropRemoteRuleSetsByTags) with a fake probe —
 * no network, production-default app sets included (CN trio + ads).
 */
class RuleSetTriageTest {

    private val profileId = 7L
    private val t0 = 1_700_000_000_000L

    private val okUrl = "https://cdn.example.com/sub-ok.srs"
    private val notFoundUrl = "https://cdn.example.com/sub-404.srs"
    private val slowUrl = "https://slow.example.com/sub-slow.srs"
    private val scriptUrl =
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-nvidia.srs"
    private val cnUrl = "https://cdn.example.com/geosite-cn.srs"
    private val geoipCnUrl = "https://cdn.example.com/geoip-cn.srs"
    private val geolocationUrl = "https://cdn.example.com/geosite-geolocation-!cn.srs"
    private val adsUrl = "https://cdn.example.com/geosite-category-ads-all.srs"

    /** A built-config shape: subscription + script + app-injected sets. */
    private fun mixedConfig(): String = JSONObject()
        .put(
            "route",
            JSONObject()
                .put(
                    "rule_set",
                    org.json.JSONArray()
                        .put(remoteSet("sub-ok", okUrl))
                        .put(remoteSet("sub-404", notFoundUrl))
                        .put(remoteSet("sub-slow", slowUrl))
                        .put(remoteSet("geosite-nvidia@cn", scriptUrl))
                        .put(remoteSet("geosite-cn", cnUrl))
                        .put(remoteSet("geoip-cn", geoipCnUrl))
                        .put(remoteSet("geosite-geolocation-!cn", geolocationUrl))
                        .put(remoteSet("geosite-category-ads-all", adsUrl))
                        .put(
                            JSONObject()
                                .put("tag", "local-set")
                                .put("type", "local")
                                .put("path", "/data/local.srs"),
                        ),
                )
                .put(
                    "rules",
                    org.json.JSONArray()
                        .put(JSONObject().put("rule_set", org.json.JSONArray().put("sub-ok").put("sub-404")).put("outbound", "proxy"))
                        .put(JSONObject().put("rule_set", "geosite-nvidia@cn").put("outbound", "direct"))
                        .put(
                            JSONObject().put("type", "logical").put("mode", "and")
                                .put(
                                    "rules",
                                    org.json.JSONArray()
                                        .put(JSONObject().put("rule_set", org.json.JSONArray().put("sub-slow").put("geosite-cn")).put("outbound", "proxy")),
                                )
                                .put("outbound", "proxy"),
                        ),
                )
                .put("final", "proxy"),
        )
        .put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    org.json.JSONArray()
                        .put(JSONObject().put("tag", "dns-remote").put("type", "https").put("server", "8.8.8.8")),
                )
                .put(
                    "rules",
                    org.json.JSONArray()
                        .put(JSONObject().put("rule_set", org.json.JSONArray().put("sub-404").put("geosite-cn")).put("server", "dns-remote"))
                        .put(JSONObject().put("rule_set", "geosite-category-ads-all").put("action", "reject")),
                ),
        )
        .put(
            "outbounds",
            org.json.JSONArray()
                .put(JSONObject().put("tag", "proxy").put("type", "direct"))
                .put(JSONObject().put("tag", "direct").put("type", "direct")),
        )
        .toString()

    private fun remoteSet(tag: String, url: String): JSONObject = JSONObject()
        .put("tag", tag)
        .put("type", "remote")
        .put("format", "binary")
        .put("url", url)
        .put("download_detour", "direct")

    /** The production probe's semantics, faked: 2xx = true, else false. */
    private fun fakeProbe(verdicts: Map<String, Boolean>): RuleSetProbe = RuleSetProbe { url -> verdicts[url] ?: true }

    private fun triage(
        content: String,
        probe: RuleSetProbe,
        records: Map<String, RuleSetTriage.Record> = emptyMap(),
        hasCacheCopy: (RuleSetTriage.Ref) -> Boolean = { false },
        nowMs: Long = t0,
    ): RuleSetTriage.Decision {
        val refs = RuleSetTriage.enumerate(content)
        val plan = RuleSetTriage.plan(refs, records, profileId, nowMs, hasCacheCopy)
        val results = RuleSetTriage.probeAll(plan.toProbe, probe)
        return RuleSetTriage.decide(plan, results, profileId, nowMs)
    }

    @Test
    fun mixedAvailabilityKeepsExactlyTheUsableSets() {
        val content = mixedConfig()
        val decision = triage(
            content,
            fakeProbe(
                mapOf(
                    okUrl to true,
                    notFoundUrl to false, // 404
                    slowUrl to false, // timeout reads as false, like the real probe
                    scriptUrl to false, // the 135 field failure
                    cnUrl to true,
                    geoipCnUrl to false,
                    geolocationUrl to true,
                    adsUrl to true,
                ),
            ),
        )
        assertEquals(
            setOf("sub-404", "sub-slow", "geosite-nvidia@cn", "geoip-cn"),
            decision.unavailableTags.toSet(),
        )

        val root = JSONObject(content)
        assertTrue(
            ConfigInboundCompat.dropRemoteRuleSetsByTags(root, decision.unavailableTags.toSet()),
        )
        // Exactly the usable remote sets survive, local set untouched.
        assertEquals(
            setOf("sub-ok", "geosite-cn", "geosite-geolocation-!cn", "geosite-category-ads-all"),
            ConfigInboundCompat.remoteRuleSetTags(root.toString()).toSet(),
        )
        val defined = RuleSetProviders.parse(root.toString()).map { it.tag }.toSet()
        assertTrue(defined.contains("local-set"))
        // No dangling reference anywhere (route + dns, logical included).
        val referenced = ConfigInboundCompat.referencedRuleSetTags(root)
        assertTrue("dangling references: ${referenced - defined}", (referenced - defined).isEmpty())
        // Kept references are intact, not merely non-dangling.
        assertTrue(referenced.containsAll(setOf("sub-ok", "geosite-cn", "geosite-category-ads-all")))
        // The logical rule kept its surviving member instead of being hollowed out.
        val routeRules = root.getJSONObject("route").getJSONArray("rules")
        var logicalInner: List<String>? = null
        for (i in 0 until routeRules.length()) {
            val rule = routeRules.getJSONObject(i)
            if (rule.optString("type") == "logical") {
                val inner = rule.getJSONArray("rules").getJSONObject(0)
                // rule_set collapses to a scalar when a single member
                // survives the strip (stripDroppedRuleSets); accept both
                // forms, like referencedRuleSetTags does.
                logicalInner = when (val raw = inner.opt("rule_set")) {
                    is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                    is String -> listOf(raw)
                    else -> emptyList()
                }
            }
        }
        assertEquals(listOf("geosite-cn"), logicalInner)
        // Probe verdicts were recorded per profile+tag+URL.
        val refs = RuleSetTriage.enumerate(content)
        val slowRef = refs.first { it.tag == "sub-slow" }
        val slowRecord = decision.newRecords[RuleSetTriage.recordKey(profileId, slowRef)]
        assertEquals(RuleSetTriage.Record(false, t0), slowRecord)
        val okRef = refs.first { it.tag == "sub-ok" }
        assertEquals(
            RuleSetTriage.Record(true, t0),
            decision.newRecords[RuleSetTriage.recordKey(profileId, okRef)],
        )
    }

    @Test
    fun freshRecordsDecideWithoutProbing() {
        val content = mixedConfig()
        val refs = RuleSetTriage.enumerate(content)
        fun recordFor(tag: String, available: Boolean, at: Long) = RuleSetTriage.recordKey(profileId, refs.first { it.tag == tag }) to
            RuleSetTriage.Record(available, at)
        val records = mapOf(
            // 1 minute old: fresh under both TTLs.
            recordFor("sub-404", available = false, at = t0 - 60_000),
            recordFor("sub-ok", available = true, at = t0 - 60_000),
            // 2 hours old: stale even for an AVAILABLE record.
            recordFor("geosite-cn", available = true, at = t0 - 2 * 3_600_000),
            // 30 minutes old: stale for an UNAVAILABLE record (10 min TTL).
            recordFor("geoip-cn", available = false, at = t0 - 30 * 60_000),
        )
        val plan = RuleSetTriage.plan(refs, records, profileId, t0) { false }
        assertEquals(listOf("sub-404"), plan.knownUnavailable.map { it.tag })
        assertTrue(plan.kept.any { it.tag == "sub-ok" })
        assertTrue(plan.toProbe.any { it.tag == "geosite-cn" })
        assertTrue(plan.toProbe.any { it.tag == "geoip-cn" })
        assertFalse(plan.toProbe.any { it.tag == "sub-404" || it.tag == "sub-ok" })
    }

    @Test
    fun cacheCopyOutranksEvenAnUnavailableRecord() {
        val content = mixedConfig()
        val refs = RuleSetTriage.enumerate(content)
        val records = mapOf(
            RuleSetTriage.recordKey(profileId, refs.first { it.tag == "geoip-cn" }) to
                RuleSetTriage.Record(false, t0),
        )
        val plan = RuleSetTriage.plan(refs, records, profileId, t0) { ref -> ref.tag == "geoip-cn" }
        // The substitute rung can serve this set locally; stripping it
        // on a network verdict would only destroy value.
        assertTrue(plan.kept.any { it.tag == "geoip-cn" })
        assertTrue(plan.knownUnavailable.none { it.tag == "geoip-cn" })
        assertTrue(plan.toProbe.none { it.tag == "geoip-cn" })
    }

    @Test
    fun probeFailureAndBudgetExhaustionKeepTheSets() {
        val content = mixedConfig()
        // A probe that throws for everything: no verdicts, no strips —
        // BoxService falls back to the untriaged config + ladder.
        val throwing = triage(content, RuleSetProbe { throw RuntimeException("probe boom") })
        assertTrue(throwing.unavailableTags.isEmpty())
        assertTrue(throwing.newRecords.isEmpty())
        // A probe slower than the whole budget: same outcome.
        val refs = RuleSetTriage.enumerate(content)
        val slowResults = RuleSetTriage.probeAll(
            refs,
            RuleSetProbe {
                Thread.sleep(300)
                true
            },
            budgetMs = 50,
            parallelism = 4,
        )
        assertTrue(slowResults.isEmpty())
    }

    @Test
    fun triageHandlesGarbageAndEmptyConfigs() {
        assertTrue(RuleSetTriage.enumerate("not json").isEmpty())
        assertTrue(RuleSetTriage.enumerate("{}").isEmpty())
        val decision = triage("{}", fakeProbe(emptyMap()))
        assertTrue(decision.unavailableTags.isEmpty())
    }

    @Test
    fun appSetTagsFlowIntoSegmentSuppression() {
        // App-injected sets triaged out by exact tag must suppress the
        // segment re-injection (D9 notices fire from there); a
        // subscription tag must NOT leak into the app suppression set.
        val suppressed = ConfigQuicOverride.ladderSuppressedRuleSets(
            dropAll = false,
            needles = emptyList(),
            tags = setOf("geosite-cn", "geosite-category-ads-all", "sub-404"),
        )
        assertEquals(setOf("geosite-cn", "geosite-category-ads-all"), suppressed)
        // The pre-136 call shape is unchanged.
        val legacy = ConfigQuicOverride.ladderSuppressedRuleSets(
            dropAll = false,
            needles = listOf("geosite-category-ads-all.srs"),
        )
        assertTrue(legacy.contains("geosite-category-ads-all"))
    }
}
