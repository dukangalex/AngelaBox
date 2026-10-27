package io.nekohasekai.sfa.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1: the delete-confirmation query — which bindings a profile deletion
 * would invalidate (ChainBindingCodec.referencing). Pure codec level so
 * it runs on the JVM; the dialog itself is a thin render of this list.
 */
class ChainBindingReferencingTest {

    private val bindings = mapOf(
        1L to ChainBinding(1L, "entry-a", 9L, "land-a"),
        2L to ChainBinding(2L, "entry-b", 9L, "land-a"),
        3L to ChainBinding(3L, "entry-c", 8L, "land-b"),
        9L to ChainBinding(9L, "entry-z", 8L, "land-b"),
    )

    @Test
    fun listsOtherProfilesBindingsLandingOnTheProfile() {
        val affected = ChainBindingCodec.referencing(bindings, 9L)
        assertEquals(listOf(1L, 2L), affected.map { it.profileId })
    }

    @Test
    fun excludesTheProfilesOwnBinding() {
        // Profile 9's own binding lands on 8; deleting 9 removes that
        // binding with the profile, so it must not be listed as affected.
        // Deleting 8 affects profiles 3 and 9.
        val affected = ChainBindingCodec.referencing(bindings, 8L)
        assertEquals(listOf(3L, 9L), affected.map { it.profileId })
    }

    @Test
    fun emptyWhenNothingLandsOnTheProfile() {
        assertTrue(ChainBindingCodec.referencing(bindings, 42L).isEmpty())
        assertTrue(ChainBindingCodec.referencing(emptyMap(), 9L).isEmpty())
    }

    @Test
    fun negativeIdYieldsEmpty() {
        assertTrue(ChainBindingCodec.referencing(bindings, -1L).isEmpty())
    }

    @Test
    fun alreadyInvalidatedBindingsAreStillListed() {
        // An already-invalidated binding still references the landing;
        // the confirmation lists it so the user sees the full dependency
        // picture before deleting.
        val withInvalidated = bindings + (4L to ChainBinding(
            4L, "entry-d", 9L, "land-a",
            invalidated = true,
            invalidReason = ChainBindings.INVALID_REASON_LANDING_DELETED,
        ))
        val affected = ChainBindingCodec.referencing(withInvalidated, 9L)
        assertEquals(listOf(1L, 2L, 4L), affected.map { it.profileId })
    }

    @Test
    fun resultIsSortedByOwnerId() {
        val shuffled = mapOf(
            9L to ChainBinding(9L, "entry-z", 8L, "land-b"),
            3L to ChainBinding(3L, "entry-c", 8L, "land-b"),
            5L to ChainBinding(5L, "entry-e", 8L, "land-b"),
        )
        val affected = ChainBindingCodec.referencing(shuffled, 8L)
        assertEquals(listOf(3L, 5L, 9L), affected.map { it.profileId })
    }
}
