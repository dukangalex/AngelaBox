package io.nekohasekai.sfa.compose.screen.log

import android.text.format.DateFormat
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.compose.util.AnsiColorUtils
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.NoticeLog
import io.nekohasekai.sfa.utils.NoticeLogRecord
import io.nekohasekai.sfa.utils.NoticeLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 「应用提示记录」页的数据源：读 NoticeLogStore 的留档（界面上一闪
 * 而过的提示的全文），渲染进与内核日志共用的 LogScreen 列表。
 * 本地文件源，不依赖服务是否在跑——启动失败时的提示也查得到。
 */
class NoticeLogViewModel : BaseLogViewModel() {

    fun loadLogs() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { NoticeLogStore.readAll() }
            allLogs.clear()
            allLogs.addAll(records.map { processRecord(it) })
            _uiState.update {
                it.copy(
                    logs = emptyList(),
                    isConnected = true,
                    errorTitle = null,
                    errorMessage = null,
                )
            }
            updateDisplayedLogs()
        }
    }

    private companion object {
        private const val ANSI_RESET = "\u001B[0m"
        private const val ANSI_RED = "\u001B[31m"
        private const val ANSI_CYAN = "\u001B[36m"
    }

    private fun processRecord(record: NoticeLogRecord): ProcessedLogEntry {
        val level = if (record.error) LogLevel.ERROR else LogLevel.INFO
        val levelName = if (record.error) "ERROR" else "INFO"
        val levelColor = if (record.error) ANSI_RED else ANSI_CYAN
        val source = if (record.source == NoticeLog.SOURCE_SUBSCRIPTION) "订阅更新" else "应用提示"
        val timestamp = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(record.timestampMs)).toString()
        val message = buildString {
            append(levelColor).append(levelName).append(ANSI_RESET)
            append("[").append(timestamp).append("] ")
            append("[").append(source).append("] ")
            append(record.title)
            if (record.reason.isNotBlank()) append("\n").append(record.reason)
            if (record.hint.isNotBlank()) append("\n").append(record.hint)
        }
        return ProcessedLogEntry(
            id = logIdGenerator.incrementAndGet(),
            entry = LogEntryData(level, AnsiColorUtils.stripAnsi(message)),
            annotatedString = AnsiColorUtils.ansiToAnnotatedString(message),
        )
    }

    override fun updateServiceStatus(status: Status) {
        _uiState.update { it.copy(serviceStatus = status) }
    }

    override fun togglePause() {
        _uiState.update { it.copy(isPaused = false) }
    }

    override fun requestClearLogs() {
        NoticeLogStore.clear()
        allLogs.clear()
        _uiState.update { it.copy(logs = emptyList()) }
        updateDisplayedLogs()
    }
}
