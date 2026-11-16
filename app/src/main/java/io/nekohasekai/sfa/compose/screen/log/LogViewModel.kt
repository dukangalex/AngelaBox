package io.nekohasekai.sfa.compose.screen.log

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.compose.util.AnsiColorUtils
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicLong

class LogViewModel :
    BaseLogViewModel(),
    CommandClient.Handler {
    companion object {
        private val maxLines = 3000
    }

    private val bufferedLogs = LinkedList<ProcessedLogEntry>()
    private val logGeneration = AtomicLong(0)
    private val sessionGeneration = AtomicLong(0)
    private val commandClient =
        CommandClient(
            scope = viewModelScope,
            connectionType = CommandClient.ConnectionType.Log,
            handler = this,
        )
    private var lastServiceStatus: Status = Status.Stopped
    private val serviceStatusFlow = MutableStateFlow(Status.Stopped)

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                serviceStatusFlow,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    private fun processLogEntry(entry: LogEntry): ProcessedLogEntry {
        val level = LogLevel.entries.find { it.priority == entry.level } ?: LogLevel.Default
        return ProcessedLogEntry(
            id = logIdGenerator.incrementAndGet(),
            entry = LogEntryData(level = level, message = entry.message),
            annotatedString = AnsiColorUtils.ansiToAnnotatedString(entry.message),
        )
    }

    override fun updateServiceStatus(status: Status) {
        lastServiceStatus = status
        serviceStatusFlow.value = status
        _uiState.update { it.copy(serviceStatus = status) }

        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        when (status) {
            Status.Stopped, Status.Stopping -> {
                sessionGeneration.incrementAndGet()
                logGeneration.incrementAndGet()
                allLogs.clear()
                bufferedLogs.clear()
                _uiState.update { it.copy(isConnected = false, isPaused = false) }
                updateDisplayedLogs()
            }

            else -> {}
        }
    }

    override fun onConnected() {
        val session = sessionGeneration.incrementAndGet()
        val generation = logGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main) {
            if (session != sessionGeneration.get()) return@launch
            if (generation == logGeneration.get()) {
                allLogs.clear()
                bufferedLogs.clear()
            }
            _uiState.update { it.copy(isConnected = true) }
            updateDisplayedLogs()
        }
    }

    override fun onDisconnected() {
        val session = sessionGeneration.incrementAndGet()
        val generation = logGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main) {
            if (session != sessionGeneration.get()) return@launch
            if (generation == logGeneration.get()) {
                allLogs.clear()
                bufferedLogs.clear()
            }
            _uiState.update { it.copy(isConnected = false) }
            updateDisplayedLogs()
        }
    }

    override fun setDefaultLogLevel(level: Int) {
        val logLevel = LogLevel.entries.find { it.priority == level } ?: error("Unknown log level: $level")
        val generation = logGeneration.get()
        viewModelScope.launch(Dispatchers.Main) {
            if (generation != logGeneration.get()) return@launch
            _uiState.update { it.copy(defaultLogLevel = logLevel) }
            updateDisplayedLogs()
        }
    }

    override fun clearLogs() {
        val generation = logGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main) {
            if (generation != logGeneration.get()) return@launch
            allLogs.clear()
            bufferedLogs.clear()
            _uiState.update { it.copy(isPaused = false) }
            updateDisplayedLogs()
        }
    }

    override fun requestClearLogs() {
        val generation = logGeneration.get()
        viewModelScope.launch {
            val sent =
                withContext(Dispatchers.IO) {
                    runCatching {
                        CommandTarget.standaloneClient().clearLogs()
                    }.isSuccess
                }
            // With the service stopped there is no broadcast to clear the UI,
            // so the local buffer is cleared directly.
            if (!sent && generation == logGeneration.get()) {
                clearLogs()
            }
        }
    }

    override fun appendLogs(message: List<LogEntry>) {
        val generation = logGeneration.get()
        val processedLogs = message.takeLast(maxLines).map { processLogEntry(it) }
        viewModelScope.launch(Dispatchers.Main) {
            if (generation != logGeneration.get()) return@launch
            if (_uiState.value.isPaused) {
                bufferedLogs.addAll(processedLogs)
                while (bufferedLogs.size > maxLines) bufferedLogs.removeFirst()
            } else {
                val totalSize = allLogs.size + processedLogs.size
                val removeCount = (totalSize - maxLines).coerceAtLeast(0)

                if (removeCount > 0) {
                    repeat(removeCount) {
                        allLogs.removeFirst()
                    }
                }

                allLogs.addAll(processedLogs)
                updateDisplayedLogs()

                if (_autoScrollEnabled.value && !_uiState.value.isPaused && !_uiState.value.isSearchActive) {
                    scrollToBottom()
                }
            }
        }
    }

    override fun togglePause() {
        val currentState = _uiState.value
        if (currentState.isPaused && bufferedLogs.isNotEmpty()) {
            val totalSize = allLogs.size + bufferedLogs.size
            val removeCount = (totalSize - maxLines).coerceAtLeast(0)

            if (removeCount > 0) {
                repeat(removeCount) {
                    allLogs.removeFirst()
                }
            }

            allLogs.addAll(bufferedLogs)
            bufferedLogs.clear()
        }

        _uiState.update { it.copy(isPaused = !it.isPaused) }
        updateDisplayedLogs()
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }
}
