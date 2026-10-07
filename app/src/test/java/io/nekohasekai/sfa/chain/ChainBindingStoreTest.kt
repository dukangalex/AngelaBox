package io.nekohasekai.sfa.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainBindingStoreTest {

    @Test
    fun roundTripKeepsSeparateProfiles() {
        val a = ChainBinding(11L, "proxy-select", 99L, "zgo")
        val b = ChainBinding(22L, "自动选择", 88L, "落地")
        val json = ChainBindingCodec.encode(mapOf(11L to a, 22L to b))
        val parsed = ChainBindingCodec.parse(json)
        assertEquals(a, parsed[11L])
        assertEquals(b, parsed[22L])
        assertEquals(2, parsed.size)
    }

    @Test
    fun parseIgnoresBrokenEntries() {
        val json = """{"11":{"entryTag":"in","landingProfileId":2,"landingTag":"out"},"bad":{},"-1":{"landingTag":"x"}}"""
        val parsed = ChainBindingCodec.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("in", parsed[11L]?.entryTag)
        assertEquals("out", parsed[11L]?.landingTag)
    }

    @Test
    fun legacyMigratesOnlyWhenMapEmpty() {
        val legacy = ChainBindingCodec.mergeLegacy(
            existing = emptyMap(),
            enabled = true,
            boundId = 7L,
            entryTag = "节点选择",
            landingId = 8L,
            landingTag = "zgo",
        )
        assertEquals(ChainBinding(7L, "节点选择", 8L, "zgo"), legacy[7L])

        val kept = ChainBinding(1L, "a", 2L, "b")
        val skipped = ChainBindingCodec.mergeLegacy(
            existing = mapOf(1L to kept),
            enabled = true,
            boundId = 7L,
            entryTag = "节点选择",
            landingId = 8L,
            landingTag = "zgo",
        )
        assertEquals(kept, skipped[1L])
        assertNull(skipped[7L])
    }

    @Test
    fun twoProfilesDoNotShareLanding() {
        val json = ChainBindingCodec.encode(
            mapOf(
                1L to ChainBinding(1L, "entry-a", 9L, "land-a"),
                2L to ChainBinding(2L, "entry-b", 8L, "land-b"),
            ),
        )
        val parsed = ChainBindingCodec.parse(json)
        assertEquals("land-a", parsed[1L]?.landingTag)
        assertEquals("land-b", parsed[2L]?.landingTag)
        assertTrue(parsed[1L]?.landingTag != parsed[2L]?.landingTag)
    }
}
