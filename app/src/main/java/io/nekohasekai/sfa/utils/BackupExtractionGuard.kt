package io.nekohasekai.sfa.utils

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reject archive directory/stream mismatches and discard staged content on failure. */
internal fun <T> extractBackupOrDiscard(
    src: File,
    staging: File,
    maxEntries: Int,
    extract: ((ZipEntry) -> (() -> Unit)) -> T,
): T =
    try {
        ZipFile(src).use { zip ->
            val remaining = mutableMapOf<String, ArrayDeque<ZipEntry>>()
            val directory = zip.entries()
            var count = 0
            while (directory.hasMoreElements()) {
                check(++count <= maxEntries) { "备份包含过多文件" }
                val entry = directory.nextElement()
                remaining.getOrPut(entry.name) { ArrayDeque() }.addLast(entry)
            }
            val result = extract { entry ->
                val entries = remaining[entry.name] ?: error("备份目录与文件不一致")
                val expected = entries.removeFirst()
                if (entries.isEmpty()) remaining.remove(entry.name)
                val verify: () -> Unit = {
                    check(entry.size == expected.size && entry.crc == expected.crc &&
                        entry.isDirectory == expected.isDirectory) { "备份目录与文件不一致" }
                }
                verify
            }
            check(remaining.isEmpty()) { "备份内容不完整" }
            result
        }
    } catch (e: Exception) {
        staging.deleteRecursively()
        throw e
    }

/** Keep extracted backup data only for the duration of a restore attempt.
 * This does not protect against process death or guarantee deletion when the filesystem refuses it. */
internal inline fun <T> withRestoreStaging(
    staging: File,
    cleanup: (File) -> Boolean = { it.deleteRecursively() },
    block: (File) -> T,
): T {
    check(!staging.exists() || cleanup(staging)) { "无法清理恢复暂存目录" }
    check(staging.mkdirs() || staging.isDirectory) { "无法创建恢复暂存目录" }
    var failure: Throwable? = null
    return try {
        block(staging)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        val cleanupFailure = try {
            if (staging.exists() && !cleanup(staging)) IllegalStateException("无法清理恢复暂存目录") else null
        } catch (error: Exception) {
            error
        }
        if (cleanupFailure != null) {
            // Restore has no transaction yet. Do not imply nothing changed or invite an unsafe retry.
            val report = IllegalStateException(
                "恢复暂存目录清理失败；恢复可能已部分或全部生效，请检查后再重试。",
                failure ?: cleanupFailure,
            )
            if (failure != null) report.addSuppressed(cleanupFailure)
            throw report
        }
    }
}
