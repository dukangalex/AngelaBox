package io.nekohasekai.sfa.compose.screen.connections

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Connection
import io.nekohasekai.sfa.compose.model.ConnectionSort
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    val allConnections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val stateFilter: ConnectionStateFilter = ConnectionStateFilter.Active,
    val sort: ConnectionSort = ConnectionSort.ByDate,
    val searchText: String = "",
    val isSearchActive: Boolean = false,
)

sealed class ConnectionsEvent : ScreenEvent {
    data class ConnectionClosed(val id: String) : ConnectionsEvent()
    data object AllConnectionsClosed : ConnectionsEvent()
}

class ConnectionsViewModel :
    BaseViewModel<ConnectionsUiState, ConnectionsEvent>(),
    CommandClient.Handler {
    private val commandClient = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Connections,
        this,
    )

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    private val _visibleCount = MutableStateFlow(0)

    private val connectionSession = ConnectionSessionStore<Connections>()
    private val snapshotRevision = AtomicLong(0)

    override fun createInitialState() = ConnectionsUiState()

    private data class ConnectionState(
        val foreground: Boolean,
        val screenOn: Boolean,
        val visibleCount: Int,
        val status: Status,
        val remoteServerId: Long?,
        val remoteConnected: Boolean,
    )

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                AppLifecycleObserver.isScreenOn,
                _visibleCount,
                _serviceStatus,
                combine(
                    RemoteControlManager.remoteServer,
                    RemoteControlManager.isConnected,
                ) { remoteServer, remoteConnected -> remoteServer?.id to remoteConnected },
            ) { foreground, screenOn, visibleCount, status, (remoteServerId, remoteConnected) ->
                ConnectionState(foreground, screenOn, visibleCount, status, remoteServerId, remoteConnected)
            }.collect { state ->
                val serviceReady =
                    if (state.remoteServerId != null) state.remoteConnected else state.status == Status.Started
                val shouldConnect = state.foreground && state.screenOn &&
                    state.visibleCount > 0 && serviceReady
                if (shouldConnect) {
                    updateState { copy(isLoading = true) }
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        _visibleCount.update { (it + if (visible) 1 else -1).coerceAtLeast(0) }
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }

    private fun clearConnectionsFor(generation: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!connectionSession.clear(generation)) return@launch
            withContext(Dispatchers.Main) {
                connectionSession.ifCurrent(generation) {
                    updateState {
                        copy(connections = emptyList(), allConnections = emptyList(), isLoading = false)
                    }
                }
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) return
        lastServiceStatus = status
        // Publish the new status immediately; a delayed stopped-status coroutine must
        // never clear data for a later started session.
        _serviceStatus.value = status
        if (RemoteControlManager.remoteServer.value == null && status != Status.Started) {
            clearConnectionsFor(connectionSession.close())
        }
    }

    fun setStateFilter(filter: ConnectionStateFilter) {
        updateState { copy(stateFilter = filter) }
        requestConnectionsRefresh()
    }

    fun setSort(sort: ConnectionSort) {
        updateState { copy(sort = sort) }
        requestConnectionsRefresh()
    }

    fun setSearchText(text: String) {
        updateState { copy(searchText = text) }
        requestConnectionsRefresh()
    }

    fun toggleSearch() {
        val newSearchActive = !currentState.isSearchActive
        updateState {
            copy(
                isSearchActive = newSearchActive,
                searchText = if (newSearchActive) searchText else "",
            )
        }
        if (!newSearchActive) {
            requestConnectionsRefresh()
        }
    }

    fun closeConnection(connectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnection(connectionId)
                withContext(Dispatchers.Main) {
                    sendEvent(ConnectionsEvent.ConnectionClosed(connectionId))
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnections()
                withContext(Dispatchers.Main) {
                    sendEvent(ConnectionsEvent.AllConnectionsClosed)
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    override fun onConnected() {
        val generation = connectionSession.open()
        val revision = snapshotRevision.get()
        viewModelScope.launch(Dispatchers.Main) {
            connectionSession.ifCurrent(generation) {
                if (revision == snapshotRevision.get()) {
                    updateState { copy(connections = emptyList(), allConnections = emptyList(), isLoading = false) }
                }
            }
        }
    }

    override fun onDisconnected() {
        // Invalidate at callback time, not after the asynchronous cleanup runs.
        clearConnectionsFor(connectionSession.close())
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        val generation = connectionSession.current()
        viewModelScope.launch(Dispatchers.Default) {
            val snapshot = connectionSession.update(generation, ::Connections) { store ->
                store.applyEvents(events)
                val criteria = uiState.value.criteria()
                val lists = buildConnectionLists(store, criteria)
                ConnectionSnapshot(lists, criteria, snapshotRevision.incrementAndGet())
            } ?: return@launch
            if (connectionSession.current() != generation) return@launch
            withContext(Dispatchers.Main) {
                connectionSession.ifCurrent(generation) {
                    updateState {
                        if (!canPublishConnectionSnapshot(snapshot.revision, snapshotRevision.get(), snapshot.criteria, criteria())) {
                            this
                        } else {
                            copy(
                                connections = snapshot.lists.connections,
                                allConnections = snapshot.lists.allConnections,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestConnectionsRefresh() {
        viewModelScope.launch(Dispatchers.Default) {
            val generation = connectionSession.current()
            val snapshot = connectionSession.read(generation) { store ->
                val criteria = uiState.value.criteria()
                val lists = buildConnectionLists(store, criteria)
                ConnectionSnapshot(lists, criteria, snapshotRevision.incrementAndGet())
            } ?: return@launch
            if (connectionSession.current() != generation) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                connectionSession.ifCurrent(generation) {
                    updateState {
                        if (!canPublishConnectionSnapshot(snapshot.revision, snapshotRevision.get(), snapshot.criteria, criteria())) {
                            this
                        } else {
                            copy(
                                connections = snapshot.lists.connections,
                                allConnections = snapshot.lists.allConnections,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildConnectionLists(
        connections: Connections,
        criteria: ConnectionCriteria,
    ): ConnectionLists {
        val allConnectionList = connections.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }

        connections.filterState(criteria.stateFilter.libboxValue)

        when (criteria.sort) {
            ConnectionSort.ByDate -> connections.sortByDate()
            ConnectionSort.ByTraffic -> connections.sortByTraffic()
            ConnectionSort.ByTrafficTotal -> connections.sortByTrafficTotal()
        }

        val connectionList = connections.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }
            .filter { it.performSearch(criteria.searchText) }

        return ConnectionLists(
            connections = connectionList,
            allConnections = allConnectionList,
        )
    }

    private fun ConnectionsUiState.criteria() = ConnectionCriteria(stateFilter, sort, searchText)

    private data class ConnectionSnapshot(
        val lists: ConnectionLists,
        val criteria: ConnectionCriteria,
        val revision: Long,
    )

    private data class ConnectionLists(
        val connections: List<Connection>,
        val allConnections: List<Connection>,
    )
}
