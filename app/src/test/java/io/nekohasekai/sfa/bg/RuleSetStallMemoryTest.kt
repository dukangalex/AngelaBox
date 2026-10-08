package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetStallMemoryTest {
    private var now = 1_000_000L
    private val memory = RuleSetStallMemory(clock = { now })

    @Test
    fun nothingRecordedKeepsRuleSets() {
        assertFalse(memory.shouldSkipRemote(1L, "net-a"))
    }

    @Test
    fun skipsOnlyWithinWindowOnSameNetwork() {
        memory.recordStall(1L, "net-a")
        assertTrue(memory.shouldSkipRemote(1L, "net-a"))
        now += RuleSetStallMemory.WINDOW_MS - 1
        assertTrue(memory.shouldSkipRemote(1L, "net-a"))
        now += 2
        assertFalse(memory.shouldSkipRemote(1L, "net-a"))
        // An expired entry is gone, not revived by time going backwards.
        now -= 10
        assertFalse(memory.shouldSkipRemote(1L, "net-a"))
    }

    @Test
    fun otherNetworkOrUnknownNetworkRetries() {
        memory.recordStall(1L, "net-a")
        assertFalse(memory.shouldSkipRemote(1L, "net-b"))
        assertFalse(memory.shouldSkipRemote(1L, null))
        // Switching back within the window still skips.
        assertTrue(memory.shouldSkipRemote(1L, "net-a"))
    }

    @Test
    fun stallOnUnknownNetworkIsNotKept() {
        memory.recordStall(1L, null)
        assertFalse(memory.shouldSkipRemote(1L, null))
        assertFalse(memory.shouldSkipRemote(1L, ""))
    }

    @Test
    fun profilesAreIndependent() {
        memory.recordStall(1L, "net-a")
        assertFalse(memory.shouldSkipRemote(2L, "net-a"))
    }

    @Test
    fun clearForgetsTheStall() {
        memory.recordStall(1L, "net-a")
        memory.clear(1L)
        assertFalse(memory.shouldSkipRemote(1L, "net-a"))
    }

    @Test
    fun newStallRestartsTheWindow() {
        memory.recordStall(1L, "net-a")
        now += RuleSetStallMemory.WINDOW_MS - 5
        memory.recordStall(1L, "net-b")
        now += 10
        assertTrue(memory.shouldSkipRemote(1L, "net-b"))
        assertFalse(memory.shouldSkipRemote(1L, "net-a"))
    }

    @Test
    fun clockGoingBackwardsDoesNotSkip() {
        memory.recordStall(1L, "net-a")
        now -= 1
        assertFalse(memory.shouldSkipRemote(1L, "net-a"))
    }
}
