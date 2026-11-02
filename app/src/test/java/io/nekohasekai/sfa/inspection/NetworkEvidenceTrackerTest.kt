package io.nekohasekai.sfa.inspection

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkEvidenceTrackerTest {
    @Test fun unchangedCapabilitiesDoNotInvalidateButChangesAndLossDo() {
        var epoch = 0
        val tracker = NetworkEvidenceTracker<String, Pair<Long, Long>, String> { epoch++ }
        tracker.available("wifi")
        tracker.capabilities("wifi", 3L to 1L)
        tracker.link("wifi", "route-a/dns-a")
        tracker.blocked("wifi", false)
        val ready = epoch
        tracker.capabilities("wifi", 3L to 1L) // e.g. bandwidth-only callback
        tracker.link("wifi", "route-a/dns-a")
        tracker.blocked("wifi", false)
        assertEquals(ready, epoch)
        tracker.capabilities("wifi", 1L to 1L) // validated lost
        assertEquals(ready + 1, epoch)
        tracker.capabilities("wifi", 1L to 2L) // transport changed
        assertEquals(ready + 2, epoch)
        tracker.link("wifi", "route-b/dns-a")
        assertEquals(ready + 3, epoch)
        tracker.blocked("wifi", true)
        assertEquals(ready + 4, epoch)
        tracker.lost("wifi")
        assertEquals(ready + 5, epoch)
        tracker.available("cell")
        assertEquals(ready + 6, epoch)
    }

    @Test fun foregroundAvailabilityAndOtherCapabilityChangesInvalidate() {
        var epoch = 0
        val tracker = NetworkEvidenceTracker<String, Pair<Long, Long>, String> { epoch++ }
        val foreground = 1L shl 19
        val validated = 1L shl 16
        val internet = 1L shl 12
        fun capabilities(bits: Long) = tracker.capabilities("cell", evidenceCapabilityBits(bits) to 1L)
        tracker.available("cell")
        capabilities(foreground or internet or validated)
        val ready = epoch
        capabilities(internet or validated) // foreground availability lost
        assertEquals(ready + 1, epoch)
        capabilities(internet) // validation lost, still invalidate
        assertEquals(ready + 2, epoch)
        capabilities(internet or (1L shl 35)) // unknown capability remains conservative
        assertEquals(ready + 3, epoch)
        tracker.link("cell", "route-changed")
        tracker.blocked("cell", true)
        tracker.lost("cell")
        assertEquals(ready + 6, epoch)
    }

    @Test fun initialCallbacksAndReappearingNetworksInvalidateConservatively() {
        var epoch = 0
        val tracker = NetworkEvidenceTracker<String, String, String> { epoch++ }
        tracker.capabilities("a", "validated")
        tracker.link("a", "dns")
        tracker.blocked("a", false)
        assertEquals(3, epoch)
        tracker.lost("a")
        tracker.available("a")
        tracker.capabilities("a", "validated")
        assertEquals(6, epoch)
    }
}
