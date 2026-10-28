package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compiled `.srs` files exist only on the generated `rule-set`
 * branch of the two SagerNet repos (2026-10-27 audit: every date-tag
 * URL 404s because a date tag addresses a source snapshot). pin()
 * therefore normalises any ref for those repos onto the branch, and
 * strips the source-layout `rule-set/` path prefix — the same file
 * sits at the branch root.
 */
class RuleSetPinsTest {
    private val geositeBranch = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs"

    @Test
    fun branchFormIsCanonical() {
        assertEquals(geositeBranch, RuleSetPins.pin(geositeBranch))
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs",
            RuleSetPins.pin("https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"),
        )
    }

    @Test
    fun deadDateTagsAreRewrittenToTheBranch() {
        // Source-snapshot layout: date tag + rule-set/ path prefix.
        assertEquals(
            geositeBranch,
            RuleSetPins.pin(
                "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@20261024005255/rule-set/geosite-cn.srs",
            ),
        )
        // Date tag with the file at the ref root.
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs",
            RuleSetPins.pin("https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@20261012/geoip-cn.srs"),
        )
        // Any other mutable ref (e.g. the default branch) normalises too.
        assertEquals(
            geositeBranch,
            RuleSetPins.pin("https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@master/rule-set/geosite-cn.srs"),
        )
    }

    @Test
    fun alternateCdnsAreNormalisedToo() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs",
            RuleSetPins.pin("https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@20261012/geoip-cn.srs"),
        )
        // A raw.githubusercontent URL is normalised through the same helper,
        // so the GitHub-raw rewrite cannot reintroduce a dead ref.
        assertEquals(
            geositeBranch,
            RuleSetPins.pin("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"),
        )
    }

    @Test
    fun foreignReposAndNonSrsPathsAreLeftAlone() {
        val thirdParty = "https://testingcf.jsdelivr.net/gh/Loyalsoldier/geoip@release/srs/cn.srs"
        assertEquals(thirdParty, RuleSetPins.pin(thirdParty))
        val notGitHub = "https://example.com/rules.srs"
        assertEquals(notGitHub, RuleSetPins.pin(notGitHub))
        // No live target is known for non-.srs paths of the pinned repos
        // (the branch ships compiled sets only), so they stay untouched.
        val sourceFile = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@master/rule-set/geosite-cn.json"
        assertEquals(sourceFile, RuleSetPins.pin(sourceFile))
    }

    @Test
    fun pinningIsIdempotent() {
        val once = RuleSetPins.pin(geositeBranch)
        assertEquals(once, RuleSetPins.pin(once))
        val fromDead = RuleSetPins.pin(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@20261024005255/rule-set/geosite-cn.srs",
        )
        assertEquals(fromDead, RuleSetPins.pin(fromDead))
    }

    @Test
    fun shippedUrlsUseTheBranch() {
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs",
            ConfigAdBlock.RULESET_URL,
        )
        assertTrue(RuleSetPins.geositeBase().contains("@${RuleSetPins.SING_GEOSITE_REF}/"))
        assertTrue(RuleSetPins.geoipBase().contains("@${RuleSetPins.SING_GEOIP_REF}/"))
        assertTrue(RuleSetPins.isPinned(ConfigAdBlock.RULESET_URL))
    }
}
