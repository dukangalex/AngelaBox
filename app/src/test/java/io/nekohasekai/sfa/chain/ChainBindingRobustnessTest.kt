package io.nekohasekai.sfa.chain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2/D3 binding robustness: codec backward compatibility, the
 * invalidate-instead-of-wipe deletion transform, and the scoped
 * fail-closed gate for a disappeared entry.
 */
class ChainBindingRobustnessTest {

    @Test
    fun oldJsonParsesAsNotInvalidatedAndNeverMaterialized() {
        // Bindings written before the D2/D3 fields existed carry none of
        // them; they must parse with the safe defaults.
        val json = """{"11":{"entryTag":"in","landingProfileId":2,"landingTag":"out"}}"""
        val parsed = ChainBindingCodec.parse(json)
        val binding = parsed[11L]
        assertEquals(ChainBinding(11L, "in", 2L, "out"), binding)
        assertFalse(binding!!.invalidated)
        assertEquals("", binding.invalidReason)
        assertFalse(binding.materialized)
    }

    @Test
    fun codecRoundTripsNewFields() {
        val binding = ChainBinding(
            profileId = 11L,
            entryTag = "in",
            landingProfileId = 2L,
            landingTag = "out",
            invalidated = true,
            invalidReason = "落地配置已被删除",
            materialized = true,
        )
        val parsed = ChainBindingCodec.parse(ChainBindingCodec.encode(mapOf(11L to binding)))
        assertEquals(binding, parsed[11L])
    }

    @Test
    fun encodeOmitsDefaultFields() {
        val json = ChainBindingCodec.encode(mapOf(11L to ChainBinding(11L, "in", 2L, "out")))
        val item = JSONObject(json).getJSONObject("11")
        assertFalse(item.has("invalidated"))
        assertFalse(item.has("invalidReason"))
        assertFalse(item.has("materialized"))
    }

    @Test
    fun deletingLandingProfileInvalidatesReferencingBindingsInsteadOfWiping() {
        val bindings = mapOf(
            1L to ChainBinding(1L, "entry-a", 9L, "land-a"),
            2L to ChainBinding(2L, "entry-b", 9L, "land-a"),
            3L to ChainBinding(3L, "entry-c", 8L, "land-b"),
            9L to ChainBinding(9L, "entry-z", 8L, "land-b"),
        )
        val (next, invalidated) = ChainBindingCodec.onProfileDeleted(bindings, 9L, "落地配置已被删除")
        assertEquals(2, invalidated)
        // The deleted profile's OWN binding is gone with it.
        assertFalse(next.containsKey(9L))
        // Referencing bindings are kept, marked, with the reason.
        assertTrue(next[1L]!!.invalidated)
        assertEquals("落地配置已被删除", next[1L]!!.invalidReason)
        assertTrue(next[2L]!!.invalidated)
        // Unrelated bindings are untouched.
        assertFalse(next[3L]!!.invalidated)
        assertEquals(3, next.size)
    }

    @Test
    fun deletingProfileDoesNotDoubleCountAlreadyInvalidated() {
        val bindings = mapOf(
            1L to ChainBinding(1L, "entry-a", 9L, "land-a", invalidated = true, invalidReason = "先前原因"),
        )
        val (next, invalidated) = ChainBindingCodec.onProfileDeleted(bindings, 9L, "落地配置已被删除")
        assertEquals(0, invalidated)
        // The original reason is kept, not overwritten.
        assertEquals("先前原因", next[1L]!!.invalidReason)
    }

    @Test
    fun deletingUnknownProfileChangesNothing() {
        val bindings = mapOf(1L to ChainBinding(1L, "entry-a", 9L, "land-a"))
        val (next, invalidated) = ChainBindingCodec.onProfileDeleted(bindings, 42L, "落地配置已被删除")
        assertEquals(0, invalidated)
        assertEquals(bindings, next)
    }

    @Test
    fun entryDisappearanceFailsClosedOnlyForMaterializedBindings() {
        val fresh = ChainBinding(1L, "gone", 2L, "out", materialized = false)
        val proven = ChainBinding(1L, "gone", 2L, "out", materialized = true)
        assertFalse(ChainBindingCodec.entryDisappearanceFailsClosed(fresh, entryMissing = true))
        assertTrue(ChainBindingCodec.entryDisappearanceFailsClosed(proven, entryMissing = true))
        // A present entry never fails closed either way.
        assertFalse(ChainBindingCodec.entryDisappearanceFailsClosed(proven, entryMissing = false))
        assertFalse(ChainBindingCodec.entryDisappearanceFailsClosed(fresh, entryMissing = false))
    }
}
