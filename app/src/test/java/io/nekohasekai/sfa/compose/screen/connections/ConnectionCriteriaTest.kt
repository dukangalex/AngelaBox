package io.nekohasekai.sfa.compose.screen.connections

import io.nekohasekai.sfa.compose.model.ConnectionSort
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCriteriaTest {
    private val initial = ConnectionCriteria(ConnectionStateFilter.Active, ConnectionSort.ByDate, "")

    @Test
    fun matchingLatestSnapshotIsDisplayed() {
        assertTrue(canPublishConnectionSnapshot(2, 2, initial, initial))
    }

    @Test
    fun olderSnapshotNeverReplacesNewerOne() {
        assertFalse(canPublishConnectionSnapshot(1, 2, initial, initial))
    }

    @Test
    fun oldSearchFilterAndSortAreNotDisplayedAfterSelectionChanges() {
        assertFalse(canPublishConnectionSnapshot(1, 1, initial, initial.copy(searchText = "proxy")))
        assertFalse(canPublishConnectionSnapshot(1, 1, initial, initial.copy(stateFilter = ConnectionStateFilter.Closed)))
        assertFalse(canPublishConnectionSnapshot(1, 1, initial, initial.copy(sort = ConnectionSort.ByTraffic)))
    }

    @Test
    fun lateDisconnectCleanupCannotEraseNewSession() {
        val store = ConnectionSessionStore<MutableList<String>>()
        val first = store.open()
        store.update(first, ::mutableListOf) { it.add("old") }
        val disconnected = store.close()
        val reconnected = store.open()
        store.update(reconnected, ::mutableListOf) { it.add("new") }
        assertFalse(store.clear(disconnected))
        assertEquals(listOf("new"), store.read(reconnected) { it.toList() })
        var displayed = "new"
        store.ifCurrent(disconnected) { displayed = "cleared" }
        assertEquals("new", displayed)
    }

    @Test
    fun delayedEventsFromOldSessionCannotContaminateNewSession() {
        val store = ConnectionSessionStore<MutableList<String>>()
        val first = store.open()
        val second = store.open()
        store.update(second, ::mutableListOf) { it.add("new") }
        assertNull(store.update(first, ::mutableListOf) { it.add("stale") })
        assertEquals(listOf("new"), store.read(second) { it.toList() })
    }

    @Test
    fun currentDisconnectClearsButReconnectDoesNotReuseOldStore() {
        val store = ConnectionSessionStore<MutableList<String>>()
        val first = store.open()
        store.update(first, ::mutableListOf) { it.add("old") }
        val disconnected = store.close()
        assertTrue(store.clear(disconnected))
        assertNull(store.update(disconnected, ::mutableListOf) { it.add("late") })
        val second = store.open()
        assertNull(store.read(second) { it.toList() })
        assertEquals(
            listOf("new"),
            store.update(second, ::mutableListOf) {
                it.add("new")
                it.toList()
            },
        )
    }
}
