package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 应用提示的持久记录（v1.0.136）：信息类提示在界面上一闪而过（见
 * NoticeFlashPlanner / NoticeFlashHost），但每条都要在这里留档，事后
 * 可查——「日志 → 应用提示记录」页与诊断导出（logs/notice_log.txt）
 * 都读这份记录。
 *
 * 一行一条 JSON（JSONL），落在 filesDir/notice-log.jsonl。留存上限：
 * 最新 [NoticeLog.MAX_RECORDS] 条且不超过 [NoticeLog.MAX_AGE_MS]，
 * 两者同时生效（见 [NoticeLog.trim]）。
 */
data class NoticeLogRecord(
    val timestampMs: Long,
    val source: String,
    val title: String,
    val reason: String,
    val hint: String,
    val error: Boolean,
)

/** 记录的纯逻辑：序列化 / 解析 / 留存裁剪 / 导出格式。无 Android 依赖，可单测。 */
object NoticeLog {
    /** OverrideStatus 的启动管线提示（系统显式、阶梯、分诊等）。 */
    const val SOURCE_START = "start"

    /** 订阅更新提示（recordImportWarnings）。 */
    const val SOURCE_SUBSCRIPTION = "subscription"

    const val MAX_RECORDS = 300
    const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

    fun serialize(record: NoticeLogRecord): String =
        JSONObject()
            .put("ts", record.timestampMs)
            .put("src", record.source)
            .put("title", record.title)
            .put("reason", record.reason)
            .put("hint", record.hint)
            .put("err", record.error)
            .toString()

    /** 坏行返回 null（跳过），不让一行损坏毁掉整份记录。 */
    fun parse(line: String): NoticeLogRecord? = runCatching {
        val json = JSONObject(line)
        NoticeLogRecord(
            timestampMs = json.optLong("ts"),
            source = json.optString("src"),
            title = json.optString("title"),
            reason = json.optString("reason"),
            hint = json.optString("hint"),
            error = json.optBoolean("err"),
        )
    }.getOrNull()

    fun parseAll(text: String): List<NoticeLogRecord> =
        text.lineSequence().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { parse(it) }
        }.toList()

    /**
     * 留存裁剪：先按年龄丢弃（时间戳在未来的是时钟异常，保留），再只留
     * 最新 [MAX_RECORDS] 条。输入输出都按时间升序。
     */
    fun trim(records: List<NoticeLogRecord>, nowMs: Long): List<NoticeLogRecord> {
        val cutoff = nowMs - MAX_AGE_MS
        return records
            .filter { it.timestampMs >= cutoff || it.timestampMs > nowMs }
            .takeLast(MAX_RECORDS)
    }

    /** 导出 / 展示用的纯文本：一Record一段，时间 + 来源 + 标题 + 正文 + 提示。 */
    fun format(records: List<NoticeLogRecord>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return records.joinToString("\n---\n") { record ->
            buildString {
                val sourceLabel = when (record.source) {
                    SOURCE_SUBSCRIPTION -> "订阅更新"
                    else -> "启动"
                }
                append(dateFormat.format(Date(record.timestampMs)))
                append(" [").append(sourceLabel).append("] ")
                append(record.title)
                if (record.error) append("（未完全生效/状态异常）")
                if (record.reason.isNotBlank()) append('\n').append(record.reason)
                if (record.hint.isNotBlank()) append('\n').append(record.hint)
            }
        }
    }
}

/**
 * 记录的文件 sink：追加写 notice-log.jsonl，写失败一律吞掉——留档是
 * 尽力而为，绝不能反过来影响启动或更新主流程。读由日志页 / 导出在
 * IO 线程调用。
 */
object NoticeLogStore {
    private const val FILE_NAME = "notice-log.jsonl"

    /** 文件超过这个大小就重写为裁剪后的留存集（顺带完成留存裁剪）。 */
    private const val COMPACT_THRESHOLD_BYTES = 512L * 1024

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notice-log").apply { isDaemon = true }
    }

    private fun file(): File = File(Application.application.filesDir, FILE_NAME)

    fun append(records: List<NoticeLogRecord>) {
        if (records.isEmpty()) return
        val snapshot = records.toList()
        executor.execute {
            runCatching {
                synchronized(lock) {
                    val target = file()
                    target.appendText(
                        snapshot.joinToString("\n", postfix = "\n") { NoticeLog.serialize(it) },
                    )
                    if (target.length() > COMPACT_THRESHOLD_BYTES) {
                        val kept = NoticeLog.trim(readAllLocked(), System.currentTimeMillis())
                        target.writeText(
                            kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n") {
                                NoticeLog.serialize(it)
                            },
                        )
                    }
                }
            }
        }
    }

    /** 阻塞读（请在 IO 线程调用）：返回裁剪后的留存集，时间升序。 */
    fun readAll(): List<NoticeLogRecord> = synchronized(lock) { readAllLocked() }

    private fun readAllLocked(): List<NoticeLogRecord> = runCatching {
        val target = file()
        if (!target.isFile) return emptyList()
        NoticeLog.trim(NoticeLog.parseAll(target.readText()), System.currentTimeMillis())
    }.getOrDefault(emptyList())

    fun clear() {
        executor.execute {
            runCatching {
                synchronized(lock) { file().delete() }
            }
        }
    }
}

/** OverrideNotice → 启动来源的日志记录。 */
fun OverrideNotice.toNoticeLogRecord(timestampMs: Long): NoticeLogRecord = NoticeLogRecord(
    timestampMs = timestampMs,
    source = NoticeLog.SOURCE_START,
    title = title,
    reason = reason,
    hint = hint,
    error = error,
)
