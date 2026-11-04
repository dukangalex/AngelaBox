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

    @Test fun separateNetworksNeverHideEachOthersChanges() {
        var epoch = 0
        val tracker = NetworkEvidenceTracker<String, String, String> { epoch++ }
        tracker.capabilities("wifi", "same")
        tracker.capabilities("cell", "same")
        tracker.link("wifi", "dns-a")
        tracker.link("cell", "dns-b")
        val ready = epoch
        tracker.link("cell", "dns-c")
        assertEquals(ready + 1, epoch)
        tracker.capabilities("wifi", "same")
        assertEquals(ready + 1, epoch)
        tracker.lost("cell")
        assertEquals(ready + 2, epoch)
        tracker.link("wifi", "dns-a")
        assertEquals(ready + 2, epoch)
    }

    @Test fun callbackSubscriptionsKeepIndependentInitialState() {
        var epoch = 0
        val defaultTracker = NetworkEvidenceTracker<String, String, String> { epoch++ }
        val physicalTracker = NetworkEvidenceTracker<String, String, String> { epoch++ }
        defaultTracker.capabilities("wifi", "internet")
        physicalTracker.capabilities("wifi", "internet")
        assertEquals(2, epoch)
        defaultTracker.capabilities("wifi", "internet")
        physicalTracker.capabilities("wifi", "internet")
        assertEquals(2, epoch)
        physicalTracker.blocked("wifi", true)
        assertEquals(3, epoch)
    }

    @Test fun bestNetworkSwitchDropsOldSnapshotsEvenWithoutLostCallback() {
        var epoch = 0
        val tracker = NetworkEvidenceTracker<String, String, String>(singleNetwork = true) { epoch++ }
        tracker.available("wifi")
        tracker.capabilities("wifi", "internet")
        tracker.available("cell")
        val switched = epoch
        tracker.capabilities("wifi", "internet")
        // A late callback must be treated as new, not suppressed by old state.
        assertEquals(switched + 1, epoch)
    }
}
