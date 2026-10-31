package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppPackageSnapshotTest {
    @Test
    fun exclusionSurvivesConsumedIteratorAndOriginalMutation() {
        val original = mutableSetOf("org.test.probe", "io.chainbox.app")
        val snapshot = PerAppPackageSnapshot(true, false, original, "io.chainbox.app")
        original.clear()
        assertTrue(snapshot.excludes)
        assertFalse(snapshot.includes)
        assertEquals(setOf("org.test.probe"), snapshot.excludeIterator().asSequence().toSet())
        assertEquals(setOf("org.test.probe"), snapshot.excludeIterator().asSequence().toSet())
    }

    @Test
    fun inclusionSurvivesConsumedIteratorAndAlwaysIncludesOwnPackage() {
        val snapshot = PerAppPackageSnapshot(true, true, setOf("org.test.probe"), "io.chainbox.app")
        assertTrue(snapshot.includes)
        assertFalse(snapshot.excludes)
        val expected = setOf("org.test.probe", "io.chainbox.app")
        assertEquals(expected, snapshot.includeIterator().asSequence().toSet())
        assertEquals(expected, snapshot.includeIterator().asSequence().toSet())
    }

    @Test
    fun disabledDoesNotProvidePackageOverrides() {
        val snapshot = PerAppPackageSnapshot(false, true, setOf("org.test.probe"), "io.chainbox.app")
        assertFalse(snapshot.includes)
        assertFalse(snapshot.excludes)
        assertFalse(snapshot.includeIterator().hasNext())
        assertFalse(snapshot.excludeIterator().hasNext())
    }
}
