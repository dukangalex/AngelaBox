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
