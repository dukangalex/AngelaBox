package io.nekohasekai.sfa.compose.screen.connections

import io.nekohasekai.sfa.compose.model.ConnectionSort
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter

/** A computed list must only be displayed for the criteria used to compute it. */
internal data class ConnectionCriteria(
    val stateFilter: ConnectionStateFilter,
    val sort: ConnectionSort,
    val searchText: String,
)

internal fun canPublishConnectionSnapshot(
    snapshotRevision: Long,
    latestRevision: Long,
    snapshotCriteria: ConnectionCriteria,
    currentCriteria: ConnectionCriteria,
): Boolean = snapshotRevision == latestRevision && snapshotCriteria == currentCriteria
