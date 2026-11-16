package io.nekohasekai.sfa.compose.screen.connections

import io.nekohasekai.sfa.compose.model.ConnectionSort
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter
import org.junit.Assert.assertFalse
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
}
