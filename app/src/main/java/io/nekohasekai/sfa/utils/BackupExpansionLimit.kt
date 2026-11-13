package io.nekohasekai.sfa.utils

import java.io.InputStream
import java.io.OutputStream

/** Bounds the expanded bytes of every ZIP entry, including entries not restored. */
internal class BackupExpansionLimit(
    private val maxEntryBytes: Long,
    private val maxTotalBytes: Long,
    private val onLimitExceeded: () -> Nothing,
) {
    private var totalBytes = 0L

    fun consume(input: InputStream, output: OutputStream?) {
        var entryBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            // Subtraction avoids arithmetic overflow even for hostile archives.
            if (count > maxEntryBytes - entryBytes || count > maxTotalBytes - totalBytes) {
                onLimitExceeded()
            }
            entryBytes += count
            totalBytes += count
            output?.write(buffer, 0, count)
        }
    }
}
