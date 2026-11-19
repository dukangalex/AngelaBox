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

    @Test
    fun emptySnapshotClearsLegacyFieldsAndCannotRemigrate() {
        val snapshot = ChainBindingCodec.snapshot(emptyMap(), 1L)
        assertEquals("{}", snapshot.json)
        assertEquals(false, snapshot.enabled)
        assertEquals(-1L, snapshot.boundProfileId)
        assertEquals("", snapshot.entryTag)
        assertEquals(-1L, snapshot.landingProfileId)
        assertEquals("", snapshot.landingTag)
        assertTrue(
            ChainBindingCodec.mergeLegacy(
                ChainBindingCodec.parse(snapshot.json),
                snapshot.enabled,
                snapshot.boundProfileId,
                snapshot.entryTag,
                snapshot.landingProfileId,
                snapshot.landingTag,
            ).isEmpty(),
        )
    }

    @Test
    fun snapshotRetainsOtherBindingsAndMaterializedState() {
        val first = ChainBinding(1L, "first", 2L, "out", materialized = true)
        val second = ChainBinding(3L, "second", 4L, "out2", invalidated = true, invalidReason = "gone")
        val snapshot = ChainBindingCodec.snapshot(mapOf(1L to first, 3L to second), 3L)
        assertEquals(mapOf(1L to first, 3L to second), ChainBindingCodec.parse(snapshot.json))
        assertEquals(true, snapshot.enabled)
        assertEquals(3L, snapshot.boundProfileId)
        assertEquals("second", snapshot.entryTag)
        assertEquals(4L, snapshot.landingProfileId)
        assertEquals("out2", snapshot.landingTag)
    }

    @Test
    fun unboundSelectedProfileDoesNotPopulateLegacyBinding() {
        val snapshot = ChainBindingCodec.snapshot(mapOf(1L to ChainBinding(1L, "entry", 2L, "out")), 9L)
        assertEquals(true, snapshot.enabled)
        assertEquals(-1L, snapshot.boundProfileId)
        assertEquals(-1L, snapshot.landingProfileId)
        assertEquals("", snapshot.entryTag)
        assertEquals("", snapshot.landingTag)
    }
}
