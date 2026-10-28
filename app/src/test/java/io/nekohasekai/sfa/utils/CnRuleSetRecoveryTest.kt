package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression (v1.0.133-beta field failure): startup died with
 * 「远程规则集下不下来，跳过之后仍然启动失败」.
 *
 * Root cause: the start ladder's rungs re-run the whole pipeline
 * (BoxService applyForRung -> ConfigQuicOverride.apply) and the
 * rule-set stage runs BEFORE the system-explicit segment in that
 * pipeline. The segment then re-injected the pinned CN rule-set
 * definitions (geosite-cn / geoip-cn / geosite-geolocation-!cn) plus
 * fresh route/DNS rules referencing them — resurrecting exactly what
 * the replace / drop rungs had just removed. When the undownloadable
 * set was one of the injected ones, EVERY rung rebuilt the same
 * failing config and the last-resort drop could never succeed.
 *
 * v1.0.134-beta fixed the CN sets and STILL failed in the field: the
 * segment has a SECOND definition injector — ConfigAdBlock — and the
 * suppression did not cover it, while this suite drove the segment
 * with adsBlock = false, a combination production never runs
 * (Settings.adsBlock defaults to true). The config below is the
 * production default combination, and the drop-recovery cases assert
 * the post-drop output contains no remote rule-set definition and no
 * reference to a dropped tag anywhere.
 *
 * D9 covers the fix: startup survival outranks the explicit layer.
 * When the ladder has given up on a set for this run, the segment
 * must not re-inject it and must not reference it; the affected
 * feature degrades with a notice.
 */
class CnRuleSetRecoveryTest {

    private val cnTags = setOf("geosite-cn", "geoip-cn", "geosite-geolocation-!cn")
    private val adsTag = ConfigAdBlock.RULESET_TAG
    private val appTags = cnTags + adsTag

    /** Production defaults (Settings): China family on, ads on, DNS protect on. */
    private fun config() = SystemExplicitSegment.Config(
        chinaIpDomainDirect = true,
        chinaPublicDnsDirect = true,
        lanDirect = true,
        privacyProtect = false,
        vpnLeakProtect = false,
        disableQuic = false,
        excludeCnQuic = false,
        dnsProtect = true,
        dnsProtectLegacyFillOnly = false,
        disableIpv6 = false,
        webrtcProtect = false,
        adsBlock = true,
    )

    /** Subscription shape: no CN rule-sets of its own; the segment is their only source. */
    private fun rawProfile(): JSONObject = JSONObject()
        .put(
            "outbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "selector")
                        .put("tag", "proxy")
                        .put("outbounds", JSONArray().put("n1")),
                )
                .put(JSONObject().put("type", "vless").put("tag", "n1").put("server", "example.com").put("server_port", 443))
                .put(JSONObject().put("type", "direct").put("tag", "direct")),
        )
        .put(
            "route",
            JSONObject()
                .put("final", "proxy")
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("domain_suffix", JSONArray().put("example.com"))
                            .put("outbound", "proxy"),
                    ),
                ),
        )
        .put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "https")
                            .put("tag", "dns-remote")
                            .put("server", "cloudflare-dns.com"),
                    ),
                )
                .put("final", "dns-remote")
                .put("rules", JSONArray()),
        )

    private fun runSegment(
        root: JSONObject,
        suppressed: Set<String> = emptySet(),
    ): SystemExplicitSegment {
        val segment = SystemExplicitSegment(root, config(), null, suppressed)
        segment.applyChinaFamily()
        segment.applyDnsProtectToggle()
        segment.applyAdsToggle()
        segment.assembleRouteRules()
        return segment
    }

    private fun definedRuleSets(root: JSONObject): List<JSONObject> {
        val sets = root.optJSONObject("route")?.optJSONArray("rule_set") ?: return emptyList()
        return (0 until sets.length()).mapNotNull { sets.optJSONObject(it) }
    }

    private fun definedRuleSetTags(root: JSONObject): Set<String> =
        definedRuleSets(root)
            .mapNotNull { it.optString("tag").takeIf { tag -> tag.isNotBlank() } }
            .toSet()

    /** True when any definition is a remote set (by type or by http(s) URL). */
    private fun hasRemoteDefinition(root: JSONObject): Boolean =
        definedRuleSets(root).any { set ->
            set.optString("type").equals("remote", true) ||
                set.optString("url").startsWith("http")
        }

    /** Every rule_set tag referenced by a rule list, nested logical rules included. */
    private fun referencedTags(rules: JSONArray?): Set<String> {
        if (rules == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            out += referencedTags(rule.optJSONArray("rules"))
            when (val raw = rule.opt("rule_set")) {
                is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { out += it }
                is JSONArray -> for (j in 0 until raw.length()) {
                    raw.optString(j).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
        }
        return out
    }

    /** Every `server` target named by DNS rules, nested logical rules included. */
    private fun dnsRuleServers(rules: JSONArray?): Set<String> {
        if (rules == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            out += dnsRuleServers(rule.optJSONArray("rules"))
            rule.optString("server").trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    private fun routeRules(root: JSONObject): JSONArray? =
        root.optJSONObject("route")?.optJSONArray("rules")

    private fun dnsRules(root: JSONObject): JSONArray? =
        root.optJSONObject("dns")?.optJSONArray("rules")

    @Test
    fun dropAllStripsSegmentInjectedSetsAndEveryReference() {
        val root = rawProfile()
        runSegment(root)
        // Precondition: the segment is the only source of the CN sets,
        // and both route and DNS rules reference them.
        assertTrue(definedRuleSetTags(root).containsAll(cnTags))
        assertTrue(referencedTags(routeRules(root)).contains("geosite-cn"))
        assertTrue(referencedTags(dnsRules(root)).contains("geosite-cn"))

        assertTrue(ConfigInboundCompat.dropAllRemoteRuleSets(root))

        assertTrue(definedRuleSetTags(root).intersect(appTags).isEmpty())
        assertFalse(hasRemoteDefinition(root))
        assertTrue(referencedTags(routeRules(root)).intersect(appTags).isEmpty())
        assertTrue(referencedTags(dnsRules(root)).intersect(appTags).isEmpty())
        // The stripped logical mirror rule must not survive as an empty
        // shell: a zero-sub-rule AND rule matches everything and would
        // silently route ALL DNS to the China mirror server.
        assertFalse(dnsRuleServers(dnsRules(root)).contains(ConfigChinaDirect.SYS_CN_DNS_TAG))
        // The D9 floor survives: China direct's hardcoded suffix rule
        // carries no rule_set, points at direct, and stays (the
        // subscription's own example.com rule points at proxy, so this
        // match is uniquely the segment's degraded China rule).
        val rules = routeRules(root)!!
        val suffixSurvives = (0 until rules.length()).any { i ->
            val rule = rules.getJSONObject(i)
            rule.has("domain_suffix") && !rule.has("rule_set") &&
                rule.optString("outbound") == "direct"
        }
        assertTrue(suffixSurvives)
    }

    @Test
    fun ladderDropThenSegmentDoesNotResurrect() {
        val root = rawProfile()
        // Ladder order inside ConfigQuicOverride.apply: the drop rung
        // runs first (a no-op on the raw profile), the segment last —
        // with the suppression the drop rung establishes, which since
        // 135 covers every app-side injector, ads included.
        ConfigInboundCompat.dropAllRemoteRuleSets(root)
        val segment = runSegment(
            root,
            suppressed = ConfigQuicOverride.ladderSuppressedRuleSets(
                dropAll = true,
                needles = emptyList(),
            ),
        )

        assertTrue(definedRuleSetTags(root).intersect(appTags).isEmpty())
        assertFalse(hasRemoteDefinition(root))
        assertTrue(referencedTags(routeRules(root)).intersect(appTags).isEmpty())
        assertTrue(referencedTags(dnsRules(root)).intersect(appTags).isEmpty())
        // Degraded, not absent: the hardcoded suffix rule is still there.
        val rules = routeRules(root)!!
        val suffixPresent = (0 until rules.length()).any { i ->
            val rule = rules.getJSONObject(i)
            rule.has("domain_suffix") && !rule.has("rule_set") &&
                rule.optString("outbound") == "direct"
        }
        assertTrue(suffixPresent)
        // …and the degradation is said out loud (D9 floor notices).
        assertTrue(
            segment.notices.any { it.reason.contains("仅按内置域名/IP 名单生效") },
        )
        assertTrue(
            segment.notices.any { it.reason.contains("广告拦截规则集已在本次启动的规则集恢复中跳过") },
        )
    }

    @Test
    fun dropRecoveryWithProductionDefaultsLeavesNoRemoteRuleSets() {
        // The real-shape gate for the 134 field failure: production
        // default switch combination (adsBlock on, China family on),
        // drop recovery. The 134 code — CN-only suppression with the
        // ads injector live — fails the remote-definition assertion.
        val root = rawProfile()
        ConfigInboundCompat.dropAllRemoteRuleSets(root)
        runSegment(
            root,
            suppressed = ConfigQuicOverride.ladderSuppressedRuleSets(
                dropAll = true,
                needles = emptyList(),
            ),
        )
        assertFalse(hasRemoteDefinition(root))
        assertTrue(definedRuleSetTags(root).intersect(appTags).isEmpty())
        assertTrue(referencedTags(routeRules(root)).intersect(appTags).isEmpty())
        assertTrue(referencedTags(dnsRules(root)).intersect(appTags).isEmpty())
    }

    @Test
    fun adsSetIsInjectedWithoutSuppression() {
        // Control for the recovery cases: with no suppression the
        // segment DOES inject the ads definition and its reject rule —
        // the shape above can therefore catch a resurrection.
        val root = rawProfile()
        runSegment(root)
        assertTrue(definedRuleSetTags(root).contains(adsTag))
        assertTrue(hasRemoteDefinition(root))
        assertTrue(referencedTags(routeRules(root)).contains(adsTag))
    }

    @Test
    fun adsOnlySuppressionSkipsAdsKeepsChinaSets() {
        val root = rawProfile()
        val segment = runSegment(root, suppressed = setOf(adsTag))
        // China family is untouched by an ads-only suppression…
        assertTrue(definedRuleSetTags(root).containsAll(cnTags))
        assertTrue(referencedTags(routeRules(root)).contains("geosite-cn"))
        // …while the ads set is neither defined nor referenced, with
        // the skip said out loud.
        assertFalse(definedRuleSetTags(root).contains(adsTag))
        assertFalse(referencedTags(routeRules(root)).contains(adsTag))
        assertTrue(
            segment.notices.any { it.reason.contains("广告拦截规则集已在本次启动的规则集恢复中跳过") },
        )
        assertTrue(segment.notices.none { it.reason.contains("仅按内置域名/IP 名单生效") })
    }

    @Test
    fun rollbackShapeWithDropStateLeavesNoRemoteRuleSets() {
        // B-2 contract at the level the JVM can drive: BoxService's
        // script-rollback rung re-applies with skipScripts plus the
        // drop state the drop rung established. Modelled here as:
        // first attempt ran the full segment (sets injected), the drop
        // rung stripped them, and the rollback re-run carries the same
        // suppression — nothing may come back.
        val root = rawProfile()
        runSegment(root)
        assertTrue(hasRemoteDefinition(root))
        assertTrue(ConfigInboundCompat.dropAllRemoteRuleSets(root))
        runSegment(
            root,
            suppressed = ConfigQuicOverride.ladderSuppressedRuleSets(
                dropAll = true,
                needles = emptyList(),
            ),
        )
        assertFalse(hasRemoteDefinition(root))
        assertTrue(definedRuleSetTags(root).intersect(appTags).isEmpty())
        assertTrue(referencedTags(routeRules(root)).intersect(appTags).isEmpty())
        assertTrue(referencedTags(dnsRules(root)).intersect(appTags).isEmpty())
    }

    @Test
    fun partialSuppressionKeepsSurvivingSets() {
        val root = rawProfile()
        runSegment(root, suppressed = setOf("geoip-cn"))
        val defined = definedRuleSetTags(root)
        assertTrue(defined.contains("geosite-cn"))
        assertTrue(defined.contains("geosite-geolocation-!cn"))
        assertFalse(defined.contains("geoip-cn"))
        assertFalse(referencedTags(routeRules(root)).contains("geoip-cn"))
        assertFalse(referencedTags(dnsRules(root)).contains("geoip-cn"))
        assertFalse(referencedTags(dnsRules(root)).contains("geosite-geolocation-!cn"))
        assertTrue(referencedTags(dnsRules(root)).contains("geosite-cn"))
    }

    @Test
    fun ladderSuppressedCnRuleSetsMapping() {
        assertEquals(
            cnTags,
            ConfigChinaDirect.ladderSuppressedCnRuleSets(dropAll = true, needles = emptyList()),
        )
        assertEquals(
            setOf("geosite-cn"),
            ConfigChinaDirect.ladderSuppressedCnRuleSets(false, listOf("geosite-cn.srs")),
        )
        assertEquals(
            setOf("geoip-cn"),
            ConfigChinaDirect.ladderSuppressedCnRuleSets(false, listOf("geoip-cn")),
        )
        assertEquals(
            setOf("geosite-geolocation-!cn"),
            ConfigChinaDirect.ladderSuppressedCnRuleSets(
                false,
                listOf("https://cdn.example.com/geosite-geolocation-!cn.srs"),
            ),
        )
        assertEquals(
            emptySet<String>(),
            ConfigChinaDirect.ladderSuppressedCnRuleSets(false, listOf("geoip-fastly.srs")),
        )
        assertEquals(
            emptySet<String>(),
            ConfigChinaDirect.ladderSuppressedCnRuleSets(false, emptyList()),
        )
    }

    @Test
    fun ladderSuppressedRuleSetsMapping() {
        // The generalised mapping the pipeline actually uses: drop-all
        // suppresses every app-side injector, CN and ads alike.
        assertEquals(
            appTags,
            ConfigQuicOverride.ladderSuppressedRuleSets(dropAll = true, needles = emptyList()),
        )
        assertEquals(
            setOf(adsTag),
            ConfigQuicOverride.ladderSuppressedRuleSets(
                false,
                listOf("geosite-category-ads-all.srs"),
            ),
        )
        assertEquals(
            setOf("geosite-cn"),
            ConfigQuicOverride.ladderSuppressedRuleSets(false, listOf("geosite-cn.srs")),
        )
        assertEquals(
            setOf("geoip-cn", adsTag),
            ConfigQuicOverride.ladderSuppressedRuleSets(
                false,
                listOf("geoip-cn.srs", "https://cdn.example.com/geosite-category-ads-all.srs"),
            ),
        )
        assertEquals(
            emptySet<String>(),
            ConfigQuicOverride.ladderSuppressedRuleSets(false, listOf("geoip-fastly.srs")),
        )
        assertEquals(
            emptySet<String>(),
            ConfigQuicOverride.ladderSuppressedRuleSets(false, emptyList()),
        )
    }
}
