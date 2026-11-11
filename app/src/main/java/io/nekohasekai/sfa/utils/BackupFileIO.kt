package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Bounds restore input and removes rejected or interrupted copies. */
internal object BackupFileIO {
    private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024

    // Error pages are untrusted server input too. Read at most 4 KiB, without
    // BufferedReader read-ahead, so even an unending error body stays bounded.
    fun readWebdavDiagnostic(input: InputStream?): String? =
        input?.use { stream ->
            val buffer = ByteArray(4096)
            var total = 0
            while (total < buffer.size) {
                val count = stream.read(buffer, total, buffer.size - total)
                if (count < 0) break
                if (count == 0) {
                    val byte = stream.read()
                    if (byte < 0) break
                    buffer[total++] = byte.toByte()
                } else {
                    total += count
                }
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }

    fun requireWithinLimit(file: File) {
        require(file.length() <= MAX_ARCHIVE_BYTES) { "备份文件过大（上限 128 MiB）" }
    }

    /** Caller owns [input]. Any rejected or interrupted copy removes its partial file. */
    fun copyToFile(
        input: InputStream,
        destination: File,
        maxBytes: Long = MAX_ARCHIVE_BYTES,
        validate: (File) -> Unit = {},
    ) {
        require(maxBytes >= 0)
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "备份文件过大（上限 128 MiB）" }
                    output.write(buffer, 0, read)
                }
            }
            validate(destination)
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
}
