package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun unknownNetworkRecordDoesNotEraseExistingStall() {
        // E-5 regression: recordStall(null) used to REMOVE the entry, so a
        // stall hit while the default-network callback had not arrived yet
        // wiped a still-valid earlier stall and the next start waited again.
        memory.recordStall(1L, "net-a")
        memory.recordStall(1L, null)
        memory.recordStall(1L, "")
        assertTrue(memory.shouldSkipRemote(1L, "net-a"))
    }

    @Test
    fun contentChangeForgetsTheStall() {
        // X-1: a stall belongs to the content that produced it. After a
        // subscription update the reload must try the NEW rule-sets, not
        // skip them because the OLD content stalled.
        memory.recordStall(1L, "net-a", "hash-old")
        assertTrue(memory.shouldSkipRemote(1L, "net-a", "hash-old"))
        assertFalse(memory.shouldSkipRemote(1L, "net-a", "hash-new"))
        // The mismatch also forgets the entry: old content is gone for good.
        assertFalse(memory.shouldSkipRemote(1L, "net-a", "hash-old"))
    }

    @Test
    fun hashlessCallersKeepLegacyBehaviour() {
        memory.recordStall(1L, "net-a", "hash-old")
        // A caller that cannot supply a hash is not blocked by one.
        assertTrue(memory.shouldSkipRemote(1L, "net-a"))
        memory.recordStall(2L, "net-a")
        assertTrue(memory.shouldSkipRemote(2L, "net-a", "hash-anything"))
    }

    @Test
    fun networkKeyIsStableAcrossReconnects() {
        // E-4: the canonical key is interface-based, so the same physical
        // network maps to the same key after a reconnect (new netId), while
        // a different interface is a different key. Case/transport casing is
        // normalized away.
        assertEquals("v2|wifi|wlan0", RuleSetStallMemory.networkKey("wlan0", "wifi"))
        assertEquals("v2|wifi|wlan0", RuleSetStallMemory.networkKey("WLAN0", "WIFI"))
        assertEquals("v2|cellular|rmnet_data0", RuleSetStallMemory.networkKey("rmnet_data0", "cellular"))
        assertEquals("v2|unknown|wlan0", RuleSetStallMemory.networkKey("wlan0", null))
        assertNull(RuleSetStallMemory.networkKey(null, "wifi"))
        assertNull(RuleSetStallMemory.networkKey("  ", "wifi"))
    }
}
