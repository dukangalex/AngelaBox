package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetPinsTest {
    private val geositeBranch = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs"

    @Test
    fun mutableBranchIsPinned() {
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@${RuleSetPins.SING_GEOSITE_TAG}/geosite-cn.srs",
            RuleSetPins.pin(geositeBranch),
        )
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@${RuleSetPins.SING_GEOIP_TAG}/geoip-cn.srs",
            RuleSetPins.pin("https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"),
        )
    }

    @Test
    fun alternateCdnsArePinnedToo() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@${RuleSetPins.SING_GEOIP_TAG}/geoip-cn.srs",
            RuleSetPins.pin("https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"),
        )
        // A raw.githubusercontent URL is pinned through the same helper, so the
        // GitHub-raw rewrite cannot reintroduce a mutable ref.
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@${RuleSetPins.SING_GEOSITE_TAG}/geosite-cn.srs",
            RuleSetPins.pin("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"),
        )
    }

    @Test
    fun immutableRefAndForeignReposAreLeftAlone() {
        val alreadyPinned = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@20260101000000/geosite-cn.srs"
        assertEquals(alreadyPinned, RuleSetPins.pin(alreadyPinned))
        val thirdParty = "https://testingcf.jsdelivr.net/gh/Loyalsoldier/geoip@release/srs/cn.srs"
        assertEquals(thirdParty, RuleSetPins.pin(thirdParty))
        val notGitHub = "https://example.com/rules.srs"
        assertEquals(notGitHub, RuleSetPins.pin(notGitHub))
    }

    @Test
    fun pinningIsIdempotent() {
        val once = RuleSetPins.pin(geositeBranch)
        assertEquals(once, RuleSetPins.pin(once))
    }

    @Test
    fun shippedUrlsArePinned() {
        assertEquals(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@${RuleSetPins.SING_GEOSITE_TAG}/geosite-category-ads-all.srs",
            ConfigAdBlock.RULESET_URL,
        )
        assertTrue(RuleSetPins.geositeBase().contains("@${RuleSetPins.SING_GEOSITE_TAG}/"))
        assertTrue(RuleSetPins.geoipBase().contains("@${RuleSetPins.SING_GEOIP_TAG}/"))
        assertFalse(RuleSetPins.geositeBase().contains("@rule-set"))
        assertFalse(RuleSetPins.geoipBase().contains("@rule-set"))
    }
}
